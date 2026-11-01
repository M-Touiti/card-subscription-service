#!/usr/bin/env python3
"""Reduces performance/results/raw/ to performance/results/summary.md.

Repeated configurations are reported as the median run, with min-max where it matters.
"""
import collections
import glob
import json
import os
import re
import statistics
import sys

RESULTS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "results")
RAW = os.path.join(RESULTS, "raw")


def load(name):
    with open(os.path.join(RAW, f"{name}.json"), encoding="utf8") as f:
        return json.load(f)


def k6_row(name):
    metrics = load(name)["metrics"]

    def get(metric, stat):
        return metrics.get(metric, {}).get(stat)

    row = {
        "ok": get("business_ok", "count") or 0,
        "ok_per_s": get("business_ok", "rate") or 0,
        "errors_5xx": get("business_5xx", "count") or 0,
        "subscribed": get("outcome_subscribed", "count") or 0,
        "failed": get("outcome_failed", "count") or 0,
        "p50": get("business_latency", "med") or 0,
        "p95": get("business_latency", "p(95)") or 0,
        "p99": get("business_latency", "p(99)") or 0,
        "max": get("business_latency", "max") or 0,
        "active": get("hikari_active", "med"),
        "pending": get("hikari_pending", "med"),
        "cpu": get("app_cpu_usage", "med"),
        "dropped": get("dropped_iterations", "count") or 0,
    }
    path = os.path.join(RAW, f"{name}.throttle.txt")
    if os.path.exists(path):
        with open(path, encoding="utf8") as f:
            vals = [int(v) for v in f.read().split()]
        if len(vals) == 6 and vals[3] > vals[0]:
            row["throttled_pct"] = 100 * (vals[4] - vals[1]) / (vals[3] - vals[0])
    return row


def runs(pattern):
    names = sorted(os.path.basename(p)[:-5] for p in glob.glob(os.path.join(RAW, f"{pattern}.json")))
    return [k6_row(n) for n in names]


def med(rows, key):
    vals = [r[key] for r in rows if r.get(key) is not None]
    return statistics.median(vals) if vals else None


def rng(rows, key):
    vals = [r[key] for r in rows]
    return f"{min(vals):,.0f}-{max(vals):,.0f}"


def n(value, digits=0):
    return "-" if value is None else f"{value:,.{digits}f}"


def console(name):
    out = []
    with open(os.path.join(RAW, f"{name}.console.txt"), encoding="utf8", errors="replace") as f:
        for line in f:
            match = re.search(r"\{.*\}", line.replace('\\"', '"'))
            if match:
                try:
                    out.append(json.loads(match.group(0)))
                except ValueError:
                    pass
    return out


def table(headers, rows):
    lines = ["| " + " | ".join(headers) + " |", "|" + "---|" * len(headers)]
    lines += ["| " + " | ".join(str(c) for c in row) + " |" for row in rows]
    return "\n".join(lines)


def step_rates(prefix):
    found = glob.glob(os.path.join(RAW, f"{prefix}-rate-*-run*.json"))
    return sorted({int(re.search(r"-rate-(\d+)-", os.path.basename(p)).group(1)) for p in found})


def step_table(prefix):
    rows = []
    for rate in step_rates(prefix):
        rs = runs(f"{prefix}-rate-{rate}-run*")
        rows.append([
            rate, n(med(rs, "ok_per_s")), n(med(rs, "p50"), 1), n(med(rs, "p95"), 1), n(med(rs, "p99"), 1),
            n(med(rs, "max")), n(sum(r["errors_5xx"] for r in rs)), n(sum(r["dropped"] for r in rs)),
            n(med(rs, "active")), n(med(rs, "pending")), n(100 * med(rs, "cpu")),
            n(med(rs, "throttled_pct")),
        ])
    return table(["Offered req/s", "Achieved req/s", "p50 ms", "p95 ms", "p99 ms", "max ms", "5xx (all runs)",
                  "Dropped (all runs)", "Pool active", "Pool waiting", "App CPU %", "CFS throttled %"], rows)


def fault_section(mode):
    events = console(f"fault-{mode}")
    marks = {e["event"]: e["t"] for e in events if e.get("kind") == "event"}
    on, off = marks["fault_on"], marks["fault_off"]
    out = [f"### fault={mode}", ""]
    transitions = []
    prev = None
    for h in sorted((e for e in events if e.get("kind") == "health"), key=lambda e: e["t"]):
        key = (h["state"], h["http"], h.get("reported"), h.get("overall"))
        if key != prev:
            transitions.append([f"{(h['t'] - on) / 1000:+.1f}", *key])
            prev = key
    out += [table(["s from fault start", "Breaker (Prometheus)", "/actuator/health HTTP", "Breaker (health)",
                   "Overall health"], transitions), ""]
    phases = collections.OrderedDict((k, []) for k in
                                     ["before", "fault, first 5 s", "fault, rest", "after, first 15 s", "after, rest"])
    for r in (e for e in events if e.get("kind") == "req"):
        t = r["t"]
        if t < on:
            phases["before"].append(r)
        elif t < on + 5000:
            phases["fault, first 5 s"].append(r)
        elif t < off:
            phases["fault, rest"].append(r)
        elif t < off + 15000:
            phases["after, first 15 s"].append(r)
        else:
            phases["after, rest"].append(r)
    rows = []
    for phase, reqs in phases.items():
        if not reqs:
            continue
        ms = sorted(r["ms"] for r in reqs)
        tally = collections.Counter(r["outcome"] for r in reqs)
        rows.append([phase, len(reqs), ms[len(ms) // 2], ms[int(len(ms) * .95)], ms[int(len(ms) * .99)], ms[-1],
                     ", ".join(f"{k} {v}" for k, v in tally.most_common())])
    out += [table(["Phase", "Requests", "p50 ms", "p95 ms", "p99 ms", "max ms", "Outcomes"], rows), ""]
    metrics = load(f"fault-{mode}")["metrics"]
    out += [f"Dropped iterations: {metrics.get('dropped_iterations', {}).get('count', 0)}. "
            f"Rows when the test ended: {open(os.path.join(RAW, f'fault-{mode}.rows.json'), encoding='utf8').read().strip()}. "
            f"Retry job: {open(os.path.join(RAW, f'fault-{mode}.recovery.json'), encoding='utf8').read().strip()}", ""]
    return "\n".join(out)


def main():
    out = ["# Load-test summary (generated by scripts/summarize.py)", ""]
    with open(os.path.join(RAW, "environment.txt"), encoding="utf8") as f:
        out += ["```", f.read().strip(), "```", ""]

    c = k6_row("calibrate-stub-50ms")
    out += ["## Stub calibration", "", f"Direct load, 200 VUs, 50 ms delay: {n(c['ok_per_s'])} req/s, "
            f"p50 {n(c['p50'], 1)} ms, p95 {n(c['p95'], 1)} ms, 5xx {n(c['errors_5xx'])}", ""]

    out += ["## Read step load (GET by id, 10k rows, median of runs)", "",
            step_table("read"), ""]
    out += ["## Write step load (POST, 50 ms external latency, pool 10, median of runs)", "",
            step_table("write"), ""]

    rows = []
    for pool in (10, 30):
        for delay in (50, 200):
            rs = runs(f"capacity-pool{pool}-delay{delay}-run*")
            service_ms = 1000 * pool / med(rs, "ok_per_s")
            rows.append([pool, delay, n(med(rs, "ok_per_s")), rng(rs, "ok_per_s"), n(service_ms, 1),
                         n(med(rs, "p50")), n(med(rs, "active")), n(100 * med(rs, "cpu")),
                         " / ".join(n(r["ok_per_s"]) for r in rs)])
    out += ["## Write capacity: pool size x external latency (150 VUs, closed model)", "",
            table(["Pool", "External ms", "Median req/s", "Range", "pool/throughput ms", "p50 ms", "Pool active",
                   "App CPU %", "Runs"], rows), ""]

    rows = []
    base = None
    for replicas in (1, 2, 3):
        rs = runs(f"scale-rest-replicas{replicas}-run*")
        rate = med(rs, "ok_per_s")
        base = base or rate
        rows.append([replicas, n(rate), rng(rs, "ok_per_s"), f"{rate / base:.2f}x", n(med(rs, "p50")),
                     n(med(rs, "p95")), n(sum(r["errors_5xx"] for r in rs)),
                     " / ".join(n(r["ok_per_s"]) for r in rs)])
    out += ["## REST horizontal scaling (100 VUs per replica, closed model)", "",
            table(["Replicas", "Median req/s", "Range", "Scale", "p50 ms", "p95 ms", "5xx", "Runs"], rows), ""]
    # Counters are cumulative, so each run's share is the difference from the previous run.
    balance = []
    for replicas in (2, 3):
        prev = None
        for run in (1, 2, 3):
            with open(os.path.join(RAW, f"scale-rest-replicas{replicas}-run{run}.balance.txt"), encoding="utf8") as f:
                cur = [int(line.split()[1]) for line in f if line.strip()]
            if prev:
                delta = [a - b for a, b in zip(cur, prev)]
                balance.append([replicas, run, " / ".join(map(str, delta))])
            prev = cur
    out += ["Requests per replica:", "", table(["Replicas", "Run", "Per replica"], balance), ""]

    groups = collections.defaultdict(list)
    for path in glob.glob(os.path.join(RAW, "kafka-*.json")):
        with open(path, encoding="utf8") as f:
            d = json.load(f)
        groups[(d["replicas"], d["partitions"])].append(d)
    rows = []
    base = None
    for key in [(1, 12), (2, 12), (3, 12), (3, 1)]:
        ds = groups[key]
        secs = statistics.median(d["drain_seconds"] for d in ds)
        rate = ds[0]["events"] / secs
        base = base or rate
        rows.append([key[0], key[1], ds[0]["events"], n(secs, 1),
                     f"{min(d['drain_seconds'] for d in ds):.1f}-{max(d['drain_seconds'] for d in ds):.1f}",
                     n(rate, 1), f"{rate / base:.2f}x", sum(d["rows"] != d["events"] for d in ds),
                     sum(d["failed"] for d in ds)])
    out += ["## Kafka drain (50 ms external latency)", "",
            table(["Replicas", "Partitions", "Events", "Median drain s", "Range s", "Events/s", "Scale",
                   "Runs with row-count mismatch", "FAILED rows"], rows), ""]

    rows = []
    for name, label in [("table-rows10000", "GET, 10k rows"),
                        ("table-rows100000", "GET, 100k rows"),
                        ("table-rows1000000", "GET, 1M rows"),
                        ("table-write-rows1000000", "POST, 1M rows")]:
        r = k6_row(name)
        rows.append([label, n(r["ok_per_s"], 1), n(r["p50"], 1), n(r["p95"], 1), n(r["p99"], 1), n(r["errors_5xx"]),
                     n(100 * r["cpu"]), n(r.get("throttled_pct"))])
    out += ["## Lookup by card_id vs. table size (20 VUs, closed model, single run)", "",
            table(["Case", "req/s", "p50 ms", "p95 ms", "p99 ms", "5xx", "App CPU %", "CFS throttled %"], rows), ""]

    out += ["## Fault injection (50 req/s offered; 40 s fault window)", "", fault_section("error"),
            fault_section("hang")]

    out += ["## Idempotency", ""]
    tally = collections.Counter()
    retry = None
    for e in console("idempotency"):
        if e.get("kind") == "race":
            tally[e["statuses"]] += 1
        elif e.get("kind") == "retry":
            retry = e
    out += ["HTTP status sets for 5 parallel POSTs per card:", "",
            table(["Statuses", "Cards"], tally.most_common()), ""]
    for suffix in ("race-rows", "retry-rows"):
        with open(os.path.join(RAW, f"idempotency.{suffix}.json"), encoding="utf8") as f:
            out += [f"`{suffix}`: {f.read().strip()}", ""]
    out += ["Retry sequence: `" + json.dumps(retry) + "`", ""]

    with open(os.path.join(RAW, "dlt.txt"), encoding="utf8") as f:
        out += ["## Invalid messages", "", "```", f.read().strip(), "```", ""]

    text = "\n".join(out)
    with open(os.path.join(RESULTS, "summary.md"), "w", encoding="utf8", newline="\n") as f:
        f.write(text)
    sys.stdout.write(text)


if __name__ == "__main__":
    main()
