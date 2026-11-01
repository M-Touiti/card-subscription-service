#!/usr/bin/env bash
# Runs the load-test suite against performance/docker-compose.perf.yml.
#
#   performance/scripts/run-suite.sh                   # every experiment
#   performance/scripts/run-suite.sh write capacity    # selected experiments
#
# Raw results go to performance/results/raw/. Tear down afterwards with:
#   docker compose -f performance/docker-compose.perf.yml down -v
set -euo pipefail
export MSYS_NO_PATHCONV=1

# pwd -W yields a native path under Git Bash; it does not exist elsewhere.
PERF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && { pwd -W 2>/dev/null || pwd; })"
RAW="$PERF_DIR/results/raw"
mkdir -p "$RAW"

STUB_ADMIN=http://127.0.0.1:18081/__admin
REPEATS="${REPEATS:-3}"
EVENTS="${EVENTS:-6000}"

dc() { docker compose -f "$PERF_DIR/docker-compose.perf.yml" "$@"; }
log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
sql() { dc exec -T postgres psql -U carduser -d cardsubdb -tAc "$1" | tr -d '\r'; }
truncate_table() { sql 'TRUNCATE card_subscription' >/dev/null; }

stub_delay() {
  curl -fsS -X POST "$STUB_ADMIN/settings" -H 'Content-Type: application/json' \
    -d "{\"fixedDelay\": $1}" >/dev/null
}

# "periods throttled_periods throttled_usec" of the first replica's CPU cgroup.
throttle_counters() {
  dc exec -T --index=1 app cat /sys/fs/cgroup/cpu.stat 2>/dev/null | tr -d '\r' \
    | awk '/^nr_periods/ {p=$2} /^nr_throttled/ {t=$2} /^throttled_usec/ {u=$2} END {print p+0, t+0, u+0}'
}

# k6 <name> <script> [ENV=value ...]: runs a script and exports its summary as <name>.json.
k6() {
  local name="$1" script="$2"; shift 2
  local env_args=()
  local kv
  for kv in "$@"; do env_args+=(-e "$kv"); done
  local before after
  before=$(throttle_counters)
  dc run --rm -T k6 run --quiet --no-usage-report \
    --summary-export "/results/raw/$name.json" \
    --console-output "/results/raw/$name.console.txt" \
    ${env_args[@]+"${env_args[@]}"} "/scripts/$script" >/dev/null
  after=$(throttle_counters)
  [ "$name" = warmup ] || echo "$before $after" > "$RAW/$name.throttle.txt"
  # Console output only matters for scripts that emit timelines.
  case "$script" in
    fault-injection.js|idempotency.js) ;;
    *) rm -f "$RAW/$name.console.txt" ;;
  esac
}

# start_app <replicas> <hikari_pool>: fresh JVMs with the given sizing.
start_app() {
  dc rm -sf app >/dev/null 2>&1 || true
  HIKARI_POOL="$2" dc up -d --wait --scale app="$1" app >/dev/null
  REPLICAS="$1"
}

# JIT-compiles every measured path; a shorter write-only warm-up visibly skewed
# the first runs of each configuration.
warm_up() {
  local vus=$((30 * REPLICAS))
  k6 warmup rest-write.js DURATION=90s VUS="$vus" RUN_ID="warm$RANDOM"
  seed 10000
  k6 warmup rest-read.js DURATION=20s VUS="$vus" ROWS=10000 LOOKUP=id
  k6 warmup rest-read.js DURATION=20s VUS="$vus" ROWS=10000 LOOKUP=card
  rm -f "$RAW/warmup.json"
  truncate_table
}

create_topic() {
  # A running consumer would auto-create the topic again straight after the delete.
  dc rm -sf app >/dev/null 2>&1 || true
  dc exec -T kafka kafka-topics --bootstrap-server kafka:29092 --delete --topic card.events \
    >/dev/null 2>&1 || true
  local attempt
  for attempt in $(seq 1 30); do
    if dc exec -T kafka kafka-topics --bootstrap-server kafka:29092 --create \
        --topic card.events --partitions "$1" --replication-factor 1 >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  log "could not create topic"; return 1
}

seed() {
  truncate_table
  sql "INSERT INTO card_subscription (id, card_id, user_id, status, requested_at, processed_at)
       SELECT 'id-' || g, 'seed-card-' || g, 'seed-user-' || g, 'SUBSCRIBED', now(), now()
       FROM generate_series(1, $1) g" >/dev/null
  sql 'ANALYZE card_subscription' >/dev/null
}

# ── Experiments ───────────────────────────────────────────────────────────────

# The stub must not be the bottleneck, or every other number is meaningless.
exp_calibrate() {
  log "calibrate: direct load on the stub"
  stub_delay 50
  k6 calibrate-stub-50ms calibrate-stub.js
}

exp_read() {
  log "read: open-model steps, primary-key lookup"
  start_app 1 10; stub_delay 50; warm_up; seed 10000
  local rate r
  for r in $(seq 1 "$REPEATS"); do
    for rate in ${READ_RATES:-250 500 1000 1500 2000}; do
      log "  read rate=$rate run=$r"
      k6 "read-rate-$rate-run$r" rest-read.js RATE="$rate" DURATION=30s ROWS=10000 LOOKUP=id
      sleep 5
    done
  done
}

exp_write() {
  log "write: open-model steps, full subscribe path at 50 ms external latency"
  start_app 1 10; stub_delay 50; warm_up
  local rate r
  for r in $(seq 1 "$REPEATS"); do
    for rate in ${WRITE_RATES:-50 100 200 300 400 500 700}; do
      log "  write rate=$rate run=$r"
      k6 "write-rate-$rate-run$r" rest-write.js RATE="$rate" DURATION=45s RUN_ID="r$rate$r"
      sleep 10
      truncate_table
    done
  done
}

# Maximum throughput across pool size and external latency. Tests whether holding
# a connection across the external call is what limits the write path.
exp_capacity() {
  log "capacity: closed-model saturation, pool x external latency"
  local pool delay r
  for pool in 10 30; do
    start_app 1 "$pool"; stub_delay 50; warm_up
    for delay in 50 200; do
      stub_delay "$delay"
      for r in $(seq 1 "$REPEATS"); do
        log "  pool=$pool delay=${delay}ms run=$r"
        k6 "capacity-pool$pool-delay$delay-run$r" rest-write.js \
          VUS=150 DURATION=40s RUN_ID="c$pool$delay$r"
        sleep 5
        truncate_table
      done
    done
  done
}

exp_scale_rest() {
  log "scale-rest: closed-model saturation, 1 to 3 replicas"
  local replicas r
  for replicas in 1 2 3; do
    start_app "$replicas" 10; stub_delay 50; warm_up
    for r in $(seq 1 "$REPEATS"); do
      log "  replicas=$replicas run=$r"
      k6 "scale-rest-replicas$replicas-run$r" rest-write.js \
        VUS=$((replicas * 100)) DURATION=40s RUN_ID="s$replicas$r"
      # Requests served per replica proves the round-robin actually spread the load.
      local i
      : > "$RAW/scale-rest-replicas$replicas-run$r.balance.txt"
      for i in $(seq 1 "$replicas"); do
        dc exec -T --index="$i" app curl -fs http://localhost:8080/actuator/prometheus \
          | grep -E 'http_server_requests_seconds_count.*uri="/api/v1/card-subscriptions"' \
          | grep 'method="POST"' | awk -v i="$i" '{sum+=$NF} END {print "replica" i, sum}' \
          >> "$RAW/scale-rest-replicas$replicas-run$r.balance.txt"
      done
      sleep 5
      truncate_table
    done
  done
}

# Drains a pre-sized backlog: <replicas> <partitions> <run>.
kafka_drain() {
  local replicas="$1" partitions="$2" run="$3"
  local name="kafka-replicas$replicas-partitions$partitions-run$run"
  dc rm -sf app >/dev/null 2>&1 || true
  sleep 3
  create_topic "$partitions"
  truncate_table
  stub_delay 50
  HIKARI_POOL=10 CARD_EVENTS_PARTITIONS="$partitions" dc up -d --wait --scale app="$replicas" app >/dev/null

  # All consumers must have joined before producing, or the run measures a rebalance.
  # Each replica runs 3 listener threads (spring.kafka.listener.concurrency).
  local members=0 waited=0
  while [ "$members" -lt $((replicas * 3)) ] && [ "$waited" -lt 120 ]; do
    members=$(dc exec -T kafka kafka-consumer-groups --bootstrap-server kafka:29092 --describe \
      --group card-subscription-consumer-group --members 2>/dev/null \
      | tr -d '\r' | grep -c 'consumer-' || true)
    sleep 2; waited=$((waited + 2))
  done
  sleep 5

  seq 1 "$EVENTS" | awk -v run="$name" '{
      printf "k-%s-%d|{\"eventId\":\"e-%d\",\"eventType\":\"CARD_CREATED\",\"cardId\":\"%s-%d\",\"userId\":\"u-%d\",\"eventTimestamp\":\"2026-10-31T10:00:00\"}\n",
        run, $1, $1, run, $1, $1
    }' | dc exec -T kafka kafka-console-producer --bootstrap-server kafka:29092 \
      --topic card.events --property parse.key=true --property 'key.separator=|' >/dev/null

  local done_rows=0 deadline=$((SECONDS + 600))
  while [ "$SECONDS" -lt "$deadline" ]; do
    done_rows=$(sql "SELECT count(*) FROM card_subscription WHERE status <> 'PENDING'")
    [ "$done_rows" -ge "$EVENTS" ] && break
    sleep 2
  done

  sql "SELECT json_build_object(
         'replicas', $replicas, 'partitions', $partitions, 'run', $run, 'events', $EVENTS,
         'rows', count(*),
         'distinct_cards', count(DISTINCT card_id),
         'subscribed', count(*) FILTER (WHERE status = 'SUBSCRIBED'),
         'failed', count(*) FILTER (WHERE status = 'FAILED'),
         'drain_seconds', EXTRACT(EPOCH FROM max(processed_at) - min(requested_at)))
       FROM card_subscription" > "$RAW/$name.json"
  log "  $(cat "$RAW/$name.json")"
}

exp_kafka() {
  log "kafka: time to drain $EVENTS events at 50 ms external latency"
  local r
  for r in $(seq 1 "$REPEATS"); do
    kafka_drain 1 12 "$r"
    kafka_drain 2 12 "$r"
    kafka_drain 3 12 "$r"
    kafka_drain 3 1 "$r"
  done
}

# Cost of the card_id lookup as the table grows. GET /card/{cardId} is used because
# every subscribe runs the same query.
exp_table_size() {
  log "table-size: card_id lookup against table size"
  start_app 1 10; stub_delay 50; warm_up
  local rows
  for rows in 10000 100000 1000000; do
    seed "$rows"
    log "  rows=$rows"
    k6 "table-rows$rows" rest-read.js VUS=20 DURATION=30s ROWS="$rows" LOOKUP=card
    sleep 5
  done
  log "  subscribe at rows=1000000"
  k6 "table-write-rows1000000" rest-write.js VUS=20 DURATION=30s RUN_ID=tw
  truncate_table
}

exp_fault() {
  log "fault: circuit breaker under injected failures"
  local mode
  for mode in error hang; do
    start_app 1 10; stub_delay 50; warm_up
    log "  fault=$mode"
    k6 "fault-$mode" fault-injection.js FAULT="$mode" RATE=50 BEFORE_S=30 FAULT_S=40 AFTER_S=50 RUN_ID="f$mode"
    sql "SELECT json_build_object(
           'rows', count(*),
           'subscribed', count(*) FILTER (WHERE status = 'SUBSCRIBED'),
           'failed', count(*) FILTER (WHERE status = 'FAILED'),
           'pending', count(*) FILTER (WHERE status = 'PENDING'))
         FROM card_subscription" > "$RAW/fault-$mode.rows.json"
    # The retry job should now recover what the fault left FAILED.
    local started=$SECONDS unrecovered=1
    while [ "$unrecovered" -gt 0 ] && [ $((SECONDS - started)) -lt 600 ]; do
      sleep 5
      unrecovered=$(sql "SELECT count(*) FROM card_subscription WHERE status <> 'SUBSCRIBED'")
    done
    echo "{\"seconds_after_test\": $((SECONDS - started)), \"not_subscribed\": $unrecovered}" \
      > "$RAW/fault-$mode.recovery.json"
    log "  $(cat "$RAW/fault-$mode.recovery.json")"
    truncate_table
  done
}

exp_idempotency() {
  log "idempotency: concurrent duplicates and retry after failure"
  start_app 1 10; stub_delay 50; warm_up
  k6 idempotency idempotency.js CARDS=200 PARALLEL=5 RETRY_CARDS=20 RUN_ID=idem
  sql "SELECT json_build_object(
         'race_cards', count(*),
         'race_cards_with_duplicate_rows', count(*) FILTER (WHERE n > 1),
         'race_total_rows', coalesce(sum(n), 0),
         'race_max_rows_for_one_card', coalesce(max(n), 0))
       FROM (SELECT card_id, count(*) n FROM card_subscription
             WHERE card_id LIKE 'idem-race-%' GROUP BY card_id) t" > "$RAW/idempotency.race-rows.json"
  sql "SELECT json_build_object(
         'retry_cards', count(*),
         'retry_cards_with_duplicate_rows', count(*) FILTER (WHERE n > 1),
         'retry_max_rows_for_one_card', coalesce(max(n), 0))
       FROM (SELECT card_id, count(*) n FROM card_subscription
             WHERE card_id LIKE 'idem-retry-%' GROUP BY card_id) t" > "$RAW/idempotency.retry-rows.json"
  truncate_table
}

# What happens to messages the service cannot process?
exp_dlt() {
  log "dlt: invalid and malformed messages"
  dc rm -sf app >/dev/null 2>&1 || true
  dc exec -T kafka kafka-topics --bootstrap-server kafka:29092 --delete --topic card.events.DLT \
    >/dev/null 2>&1 || true
  create_topic 1
  export CARD_EVENTS_PARTITIONS=1
  start_app 1 10; stub_delay 50
  unset CARD_EVENTS_PARTITIONS
  sleep 15
  local out="$RAW/dlt.txt"
  produce() {
    printf '%s\n' "$1" | dc exec -T kafka kafka-console-producer \
      --bootstrap-server kafka:29092 --topic card.events >/dev/null
  }
  dlt_count() {
    dc exec -T kafka kafka-run-class kafka.tools.GetOffsetShell --bootstrap-server kafka:29092 \
      --topic card.events.DLT 2>/dev/null | tr -d '\r' | awk -F: '{s+=$3} END {print s+0}'
  }
  good_status() {
    sql "SELECT coalesce(string_agg(status, ','), 'no row') FROM card_subscription WHERE card_id = '$1'"
  }
  : > "$out"

  produce '{"eventId":"e1","eventType":"CARD_CREATED","cardId":"dlt-good-1","userId":"u1","eventTimestamp":"2026-10-31T10:00:00"}'
  sleep 15
  echo "valid message before tests: $(good_status dlt-good-1); DLT records: $(dlt_count)" | tee -a "$out"

  produce '{"eventId":"e2","eventType":"CARD_CREATED","cardId":"","userId":"u1","eventTimestamp":"2026-10-31T10:00:00"}'
  produce '{"eventId":"e3","eventType":"CARD_CREATED","cardId":"dlt-good-2","userId":"u1","eventTimestamp":"2026-10-31T10:00:00"}'
  sleep 20
  echo "blank cardId, then valid message, after 20 s: valid=$(good_status dlt-good-2); DLT records: $(dlt_count)" | tee -a "$out"

  # API outage: does a failed external call reach the retry and DLT path?
  curl -fsS -X POST "$STUB_ADMIN/mappings" -H 'Content-Type: application/json' -d '{
    "id":"5a1f0c6e-0000-4000-8000-0000000000fd","priority":1,
    "request":{"method":"POST","urlPath":"/api/v1/subscribe-new-card"},
    "response":{"status":500,"body":"injected fault"}}' >/dev/null
  produce '{"eventId":"e4","eventType":"CARD_CREATED","cardId":"dlt-outage","userId":"u1","eventTimestamp":"2026-10-31T10:00:00"}'
  sleep 15
  echo "external API returning 500, after 15 s: row=$(good_status dlt-outage); DLT records: $(dlt_count)" | tee -a "$out"
  curl -fsS -X DELETE "$STUB_ADMIN/mappings/5a1f0c6e-0000-4000-8000-0000000000fd" >/dev/null
  sleep 30
  echo "API recovered, 30 s later: row=$(good_status dlt-outage)" | tee -a "$out"

  produce 'this is not json'
  produce '{"eventId":"e5","eventType":"CARD_CREATED","cardId":"dlt-good-3","userId":"u1","eventTimestamp":"2026-10-31T10:00:00"}'
  sleep 30
  echo "malformed JSON, then valid message, after 30 s: valid=$(good_status dlt-good-3); DLT records: $(dlt_count)" | tee -a "$out"
  echo "log output over 5 s afterwards (lines bytes): $(dc logs --no-log-prefix --since 5s app 2>/dev/null | wc -lc)" | tee -a "$out"
  dc rm -sf app >/dev/null 2>&1 || true
  create_topic 12
  start_app 1 10
  truncate_table
}

environment() {
  {
    echo "date: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "git_commit: $(git -C "$PERF_DIR" rev-parse --short HEAD)$(git -C "$PERF_DIR" diff --quiet HEAD -- ':(top)' ':(top,exclude)performance' || echo ' plus uncommitted changes')"
    echo "docker_server: $(docker version --format '{{.Server.Version}}')"
    docker info --format 'docker_vm_cpus: {{.NCPU}}
docker_vm_mem_bytes: {{.MemTotal}}
docker_os: {{.OperatingSystem}}'
    echo "repeats: $REPEATS"
    echo "kafka_events: $EVENTS"
  } > "$RAW/environment.txt"
}

# ── Main ──────────────────────────────────────────────────────────────────────

ALL=(calibrate read write capacity scale_rest kafka table_size fault idempotency dlt)
SELECTED=("$@")
[ "${#SELECTED[@]}" -gt 0 ] || SELECTED=("${ALL[@]}")

log "building image and starting dependencies"
dc build app >/dev/null
dc up -d --wait zookeeper postgres stub >/dev/null
dc up -d kafka >/dev/null
sleep 10
create_topic 12
start_app 1 10 # creates the schema through Flyway, as in docker/docker-compose.yml
environment

for exp in "${SELECTED[@]}"; do
  "exp_${exp//-/_}"
done
log "done"
