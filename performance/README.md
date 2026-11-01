# Load-Test Report

Measured capacity, scaling and failure behaviour of the card subscription service, run on 2026-10-31 and 2026-11-01 against the fixes on top of commit `1b1923d`.

**In short:** the first run of these tests found eight defects, and all eight are now fixed and re-measured. Writes are about three times faster on one replica, and duplicates, a stuck Kafka partition and stranded failures no longer occur. The limits that remain are listed under [Remaining limits](#remaining-limits).

## Key results

All figures use a 50 ms external API, with 2 CPUs and 1 GB per replica. "Before" is the [first run](results/baseline-1b1923d/summary.md), on commit `1b1923d` with no fixes.

| Question | Before | After |
|---|---|---|
| Write capacity, 1 replica | 159-170 req/s | **about 490 req/s**; p95 67 ms at 500 req/s offered, no 5xx |
| REST writes, 1 → 3 replicas | 165 → 476 req/s (2.88x) | 418 → **1,271 req/s (3.04x)**, no 5xx |
| Kafka, 1 → 3 replicas | 14.4 → 38.7 events/s (2.68x, 6 partitions, 1 thread) | 64.9 → **175 events/s (2.70x)** |
| Kafka delivery on the happy path | One row per event | **One row per event** across 72,000 events in 12 runs |
| 5 parallel POSTs for each of 200 cards | 991 rows | **200 rows** |
| Retrying 20 cards after an outage | 500 on every later call | **All 20 `SUBSCRIBED`** |
| Malformed Kafka message | Partition stopped, about 56 MB/s of logs | **Sent to the DLT**; next message processed, no log flood |
| API hangs: breaker open, slowest request | About 11 s, 19.9 s | **4 s, 3.0 s** |
| Rows left `FAILED` after a 40 s outage | 2,015, never retried | **0**, within 5-7 minutes |
| Subscribe at one million rows | 11.5 req/s | **338 req/s**, limited by the 20 test users |

![Write latency against offered load](results/charts/write-latency.svg)

![Throughput scaling with replicas](results/charts/scaling.svg)

## How to read these numbers

- **They come from one laptop, not production hardware.** Every container, including the load generator, shared one machine, so treat the shape of each result and the ratios as more meaningful than the absolute values.
- **The external API is a stub** with a fixed delay, so real API latency variation is not represented.
- **Postgres and Kafka CPU were not measured.** Results that hit the app's CPU limit say nothing about how much headroom they have.
- **Scaling was tested up to 3 replicas only,** and run-to-run variation is visible: 1 replica ranged from 353 to 441 req/s, so 3.04x means "about linear", not better than linear.
- **Single-run results:** the table-size, fault, idempotency and invalid-message experiments ran once. Everything else is the median of 3 runs.
- **Mixed configuration:** only the Kafka experiment ran with the final 4 listener threads per replica. The others ran with 3, but their listeners were idle, so the change is not expected to affect them. This was not re-measured.
- **Not tested:** soak or long-duration behaviour, broker failure, and replica crashes or rebalances under load.

## Test setup

| Item | Detail |
|---|---|
| Host | Intel i7-13620H, 16 logical CPUs, 32 GB RAM, Windows 11, Docker Desktop 29.4.3 |
| App | Image built from the repo `Dockerfile`, 2 CPUs and 1 GB per replica |
| Dependencies | PostgreSQL 16 (2 CPUs, 1 GB), Kafka 7.6.0 (2 CPUs, 2 GB), WireMock 3.9.1 (2 CPUs, 1 GB) |
| Load generator | k6 0.54.0 (4 CPUs), on the same Docker network |
| App configuration | As shipped, including `DEBUG` logging for `com.demo.cardsubscription`; only `HIKARI_POOL` varies, and only where stated |
| Warm-up | 130 s of writes and reads on each new JVM, excluded from results (Kafka drain excepted) |

Direct calibration measured the stub at about 3,200 req/s, well above the 1,433 req/s peak it received from the service.

## Results

### Write path: where capacity runs out

`POST /api/v1/card-subscriptions` with a unique card per request, at a fixed arrival rate. Median of 3 runs, 45 s each.

| Offered req/s | Achieved req/s | p50 ms | p95 ms | p99 ms | 5xx | Dropped by k6, all runs | Pool waiting | App CPU % | CPU-throttled periods % |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 50 | 50 | 61 | 71 | 77 | 0 | 0 | 0 | 10 | 0 |
| 100 | 100 | 57 | 63 | 68 | 0 | 0 | 0 | 12 | 0 |
| 200 | 200 | 56 | 60 | 65 | 0 | 0 | 0 | 20 | 0 |
| 300 | 300 | 56 | 61 | 69 | 0 | 0 | 0 | 29 | 1 |
| 400 | 400 | 56 | 65 | 128 | 0 | 0 | 0 | 38 | 5 |
| 500 | 499 | 57 | 67 | 126 | 0 | 113 | 0 | 49 | 6 |
| 700 | 482 | 1,422 | 5,597 | 6,308 | 0 | 27,862 | 146 | 99 | 93 |

Latency stays at the external call's 50 ms plus about 6 ms up to 500 req/s. At 700 req/s the app reaches its CPU limit and is throttled in 93% of periods; throughput holds and there are no 5xx, but p50 rises to 1.4 s.

### What limits writes now

Before the fix, a database connection was held during the external call, so capacity followed `pool / latency`. Saturation test, 150 concurrent users, median of 3 runs:

| Pool | External latency | Before, req/s | After, req/s (range) | App CPU % after |
|---:|---:|---:|---:|---:|
| 10 | 50 ms | 159 | 407 (330-428) | 99 |
| 10 | 200 ms | **44** | **510** (497-516) | 92 |
| 30 | 50 ms | 366 | 411 (395-438) | 99 |
| 30 | 200 ms | 132 | 458 (442-474) | 93 |

Neither pool size nor external latency sets the limit now: a 4x slower partner no longer reduces throughput, and CPU is at or near its limit throughout. Throughput at 200 ms was higher than at 50 ms in these runs. The cause was not isolated.

### Horizontal scaling

**REST writes,** 100 concurrent users per replica.

| Replicas | Req/s (range) | Scale | p50 ms | p95 ms | 5xx |
|---:|---:|---:|---:|---:|---:|
| 1 | 418 (353-441) | 1.00x | 198 | 521 | 0 |
| 2 | 924 (911-928) | 2.21x | 190 | 486 | 0 |
| 3 | 1,271 (1,098-1,433) | 3.04x | 201 | 490 | 0 |

**Kafka,** time to process a backlog of 6,000 events, with 12 partitions and 4 listener threads per replica:

| Replicas | Partitions | Drain time, s (range) | Events/s | Scale |
|---:|---:|---:|---:|---:|
| 1 | 12 | 92.4 (91.3-93.0) | 64.9 | 1.00x |
| 2 | 12 | 63.0 (61.4-64.4) | 95.2 | 1.47x |
| 3 | 12 | 34.3 (34.0-35.2) | 175.1 | 2.70x |
| 3 | **1** | 353.3 (353.1-357.1) | 17.0 | **0.26x** |

One listener thread handles about 17 events/s, as the last row shows, because a partition is consumed by one thread at a time. A backlog therefore drains only as fast as the thread with the most partitions: 3 partitions at 1 replica, 2 at 2 replicas and 1 at 3 replicas. That is why 2 replicas gain less than 3.

The first version of the fix used 3 threads per replica, and the tests showed the same effect: 3 replicas drained no faster than 2 (67.2 s against 68.9 s, 89 against 87 events/s), because 9 threads on 12 partitions still leave some threads with 2. Those raw files were overwritten by the 4-thread re-run. To scale further, keep total threads a divisor or multiple of the partition count, and add partitions beyond 12 threads.

### Read path

`GET /api/v1/card-subscriptions/{id}` on 10,000 rows. Median of 3 runs, 30 s each. No 5xx at any rate.

| Offered req/s | Achieved req/s | p50 ms | p95 ms | p99 ms | Dropped by k6, all runs | CPU-throttled periods % |
|---:|---:|---:|---:|---:|---:|---:|
| 250 | 250 | 1.2 | 3.2 | 5.3 | 0 | 0 |
| 500 | 500 | 0.7 | 1.9 | 3.3 | 0 | 0 |
| 1,000 | 1,000 | 0.7 | 1.6 | 3.7 | 0 | 0 |
| 1,500 | 1,500 | 0.7 | 1.6 | 13.7 | 0 | 3 |
| 2,000 | 1,991 | 0.7 | 51 | 216 | 4,839 | 13 |

Tail latency grows as CPU throttling appears, which fits stalls caused by the 2-CPU limit, though the cause was not isolated. These tails are better than in the first run (p95 72 ms at 1,000 req/s), but the fixes did not target reads, so that difference is not attributed to them.

### Lookup cost as the table grows

The unique constraint on `card_id` also serves as its index. Single run, 20 concurrent users.

| Operation | Rows | Before (no index), req/s | After, req/s | After, p50 ms | After, p95 ms |
|---|---:|---:|---:|---:|---:|
| `GET /card/{cardId}` | 10,000 | 664 | 3,102 | 1.6 | 70 |
| `GET /card/{cardId}` | 100,000 | 100 | 3,121 | 1.6 | 69 |
| `GET /card/{cardId}` | 1,000,000 | 9.3 | **2,826** | 1.8 | 70 |
| `POST` subscribe | 1,000,000 | 11.5 | **338** | 59 | 64 |

Reads now cost almost the same at every size. They were CPU-limited, with about 95% of periods throttled, which also explains the p95. The `POST` figure is limited by the test: 20 users at 59 ms each is about 340 req/s, and CPU was at 37%.

### External API failures

Steady 50 req/s for 120 s, with a fault during the middle 40 s. Breaker state was sampled once per second.

**API returns HTTP 500 immediately**

| Phase | Requests | p50 ms | p99 ms | Max ms | Outcome |
|---|---:|---:|---:|---:|---|
| Before | 1,502 | 60 | 75 | 87 | `SUBSCRIBED` (2 `FAILED` at the fault boundary) |
| During fault | 2,000 | 8 | 23 | 30 | All `FAILED` |
| First 15 s after | 750 | 60 | 78 | 80 | 736 `SUBSCRIBED`, 14 `FAILED` |
| Remainder | 1,749 | 60 | 78 | 111 | All `SUBSCRIBED` |

The breaker opened 1 s into the fault and closed about 1 s after it ended. No requests were dropped. 1,698 rows were `FAILED` when the test ended, and the retry job brought them all to `SUBSCRIBED` within 283 s.

**API hangs past the 3 s read timeout**

| Phase | Requests | p50 ms | p99 ms | Max ms | Outcome |
|---|---:|---:|---:|---:|---|
| Fault, first 5 s | 224 | 3,010 | 3,028 | 3,044 | All `FAILED` |
| Fault, rest | 1,750 | 8 | 25 | 3,021 | All `FAILED` |
| First 15 s after | 750 | 8 | 72 | 79 | 135 `SUBSCRIBED`, **615 `FAILED`** |
| Remainder | 1,749 | 60 | 75 | 91 | All `SUBSCRIBED` |

The breaker opened 4 s in, against about 11 s before, and k6 dropped 26 requests, against 204. `/actuator/health` returned 200 for the whole test and reported the same breaker state as Prometheus.

One result is worse than before. The fault ended while the breaker was open, so it closed only about 13 s later, and the 615 requests in that gap were recorded `FAILED` where the first run recovered within about 1 s. Half-open timing decides this, so it varies with when a fault ends. The retry job then cleared all 2,355 `FAILED` rows within 394 s, and in the first run those rows were never retried.

### Idempotency

| Test | Before | After |
|---|---|---:|
| Rows from 5 parallel POSTs for each of 200 cards | 991 | **200** |
| Cards with duplicate rows | 199 | **0** |
| 20 cards: POST during outage | `FAILED` | `FAILED` |
| Same cards: POST after recovery | 500 | **`SUBSCRIBED`** |
| Same cards: third POST, then GET | 500 | **`SUBSCRIBED`, 200** |

### Invalid Kafka messages

| Message | Result |
|---|---|
| Blank `cardId` | Reaches `card.events.DLT` within 20 s; the next message is processed |
| Malformed JSON | Reaches the DLT; the next valid message is `SUBSCRIBED` within 30 s; 0 log lines in the 5 s afterwards |
| Valid, but the API returns 500 | `FAILED` row, nothing in the DLT; `SUBSCRIBED` within 30 s of the API recovering |

## Findings

All eight findings are fixed, and a ninth appeared while re-measuring the fixes.

| # | Severity | Finding | Fix | Evidence after the fix | Regression test |
|---|---|---|---|---|---|
| F1 | **High** | Idempotency failed under concurrency and on retry: 991 rows instead of 200, then 500s on every call | Unique constraint on `card_id`; atomic claim; retry updates the same row | [Idempotency](#idempotency) | `CardSubscriptionRestIT` |
| F2 | **High** | A malformed Kafka message stopped its partition, logging about 56 MB/s | `ErrorHandlingDeserializer`; byte-array template to the DLT | [Invalid messages](#invalid-kafka-messages) | `CardSubscriptionKafkaIT` |
| F3 | Medium | An external call held a DB connection, capping writes at `pool / latency` | No transaction around the call | [Capacity](#what-limits-writes-now) | `CardSubscriptionRestIT` |
| F4 | Medium | No index on `card_id` | Provided by the F1 constraint | [Table size](#lookup-cost-as-the-table-grows) | `SchemaUpgradeIT` covers the constraint |
| F5 | Medium | Nothing retried `FAILED` rows: 2,015 stranded after a 40 s fault | `SubscriptionRetryJob` with back-off | [API failures](#external-api-failures) | `CardSubscriptionRestIT` |
| F6 | Medium | A hanging API stalled the service for about 11 s, including health checks | 3 s read timeout; 2 s slow-call threshold | [API failures](#external-api-failures) | Load test only |
| F7 | Low | Breaker state was missing from `/actuator/health` | Health indicator enabled, without failing overall health | [API failures](#external-api-failures) | `CardSubscriptionRestIT` |
| F8 | Low | Each replica consumed Kafka serially at about 14 events/s, on one partition by default | 12 partitions created at startup; 4 listener threads | [Scaling](#horizontal-scaling) | `CardSubscriptionKafkaIT` checks partitions |
| F9 | Low | Found in re-testing: with 3 threads per replica, a third replica added nothing | 4 threads per replica | [Scaling](#horizontal-scaling) | Load test only |

## Remaining limits

- **The external API can see a repeat call.** If a call times out after taking effect, the retry calls again, so the partner should deduplicate by `cardId`.
- **Responses are not always final.** A concurrent request that loses the claim gets `201` with `PENDING`, and a failure gets `201` with `FAILED`.
- **Retries are paced.** One replica retries at most 50 rows per 5 s, which is why a 40 s outage took 5-7 minutes to clear.
- **Long outages exhaust retries.** Calls rejected by an open breaker use attempts, and 8 attempts span about an hour. After that a row stays `FAILED` until the card is requested again.
- **Recovery from a hang can lag by up to one open period,** as the hang results show.
- **Two replicas use Kafka unevenly:** 8 threads on 12 partitions gives only 1.47x.
- **Each replica has its own breaker,** so replicas can briefly disagree about the API.

## Reproducing

You need Docker, bash (Git Bash on Windows), `curl` and Python 3. The full suite takes about 2.5 hours and overwrites the files in `results/raw/`.

```bash
bash performance/scripts/run-suite.sh                 # all experiments
bash performance/scripts/run-suite.sh write capacity  # or a selection
python performance/scripts/summarize.py               # writes results/summary.md
python performance/scripts/charts.py                  # writes results/charts/
docker compose -f performance/docker-compose.perf.yml down -v
```

Experiments: `calibrate`, `read`, `write`, `capacity`, `scale_rest`, `kafka`, `table_size`, `fault`, `idempotency`, `dlt`. The stack is isolated from `docker/docker-compose.yml`, using its own project, network and volumes.

Raw k6 output is in [`results/raw/`](results/raw/), and every "after" table can be traced through [`results/summary.md`](results/summary.md). The "before" data is in [`results/baseline-1b1923d/`](results/baseline-1b1923d/). The suite ran in three invocations, so `raw/environment.txt` shows only the last one, the Kafka pass.
