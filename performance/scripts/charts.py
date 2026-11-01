#!/usr/bin/env python3
"""Renders the report's SVG charts from performance/results/raw/. Standard library only."""
import glob
import json
import math
import os
import statistics

from summarize import RAW, RESULTS, med, runs, step_rates

STYLE = """
<style>
  svg { --surface:#fcfcfb; --primary:#0b0b0b; --secondary:#52514e; --muted:#6f6d68; --grid:#e1e0d9; --axis:#c3c2b7;
        --s1:#2a78d6; --s2:#eb6834; --ref:#898781; }
  @media (prefers-color-scheme: dark) {
    svg { --surface:#1a1a19; --primary:#f0efec; --secondary:#c3c2b7; --muted:#a3a198; --grid:#2c2c2a; --axis:#383835;
          --s1:#3987e5; --s2:#d95926; --ref:#898781; }
  }
  text { font-family: system-ui, -apple-system, "Segoe UI", sans-serif; }
  .title { font-size: 16px; font-weight: 600; fill: var(--primary); }
  .sub { font-size: 12.5px; fill: var(--secondary); }
  .tick { font-size: 12px; fill: var(--muted); font-variant-numeric: tabular-nums; }
  .axis-title { font-size: 12px; fill: var(--secondary); }
  .label { font-size: 12.5px; fill: var(--primary); }
  .grid { stroke: var(--grid); stroke-width: 1; }
  .base { stroke: var(--axis); stroke-width: 1; }
  .line { fill: none; stroke-width: 2; stroke-linejoin: round; stroke-linecap: round; }
  .dot { stroke: var(--surface); stroke-width: 2; }
</style>"""

W, H = 720, 420
LEFT, RIGHT, TOP, BOTTOM = 62, 150, 116, 52


def frame(title, subtitle, desc, body):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}" role="img" '
            f'aria-label="{desc}"><title>{title}</title><desc>{desc}</desc>{STYLE}'
            f'<rect width="{W}" height="{H}" style="fill:var(--surface)"/>'
            f'<text class="title" x="20" y="30">{title}</text><text class="sub" x="20" y="50">{subtitle}</text>'
            f'{body}</svg>\n')


def legend(items):
    out, x = [], LEFT
    for colour, text, width in items:
        out.append(f'<line x1="{x}" x2="{x + 20}" y1="71" y2="71" class="line" style="stroke:{colour}"/>'
                   f'<text class="tick" style="fill:var(--secondary)" x="{x + 26}" y="75">{text}</text>')
        x += width
    return "".join(out)


def series(points, colour, labels=None):
    path = " ".join(f"{x:.1f},{y:.1f}" for x, y in points)
    out = f'<polyline class="line" points="{path}" style="stroke:{colour}"/>'
    out += "".join(f'<circle class="dot" cx="{x:.1f}" cy="{y:.1f}" r="5" style="fill:{colour}"/>' for x, y in points)
    return out + (labels or "")


def write_latency():
    rates = step_rates("write")
    top = rates[-1]
    rows = {r: runs(f"write-rate-{r}-run*") for r in rates}
    p50 = {r: med(rows[r], "p50") for r in rates}
    p95 = {r: med(rows[r], "p95") for r in rates}
    achieved = {r: med(rows[r], "ok_per_s") for r in rates}
    px0, px1, py0, py1 = LEFT, W - RIGHT, H - BOTTOM, TOP
    x = lambda v: px0 + (v / top) * (px1 - px0)
    y = lambda v: py0 - (math.log10(v) - 1) / 3 * (py0 - py1)
    body = []
    for tick, text in [(10, "10"), (100, "100"), (1000, "1K"), (10000, "10K")]:
        cls = "base" if tick == 10 else "grid"
        body.append(f'<line class="{cls}" x1="{px0}" x2="{px1}" y1="{y(tick):.1f}" y2="{y(tick):.1f}"/>'
                    f'<text class="tick" x="{px0 - 10}" y="{y(tick) + 4:.1f}" text-anchor="end">{text}</text>')
    for tick in range(100, top + 1, 100):
        body.append(f'<text class="tick" x="{x(tick):.1f}" y="{py0 + 20}" text-anchor="middle">{tick}</text>')
    body.append(f'<text class="axis-title" x="{(px0 + px1) / 2}" y="{H - 10}" text-anchor="middle">Offered load (req/s)</text>')
    body.append(f'<text class="axis-title" x="{px0 - 42}" y="{py1 - 8}">Latency (ms, log scale)</text>')
    limit = statistics.median(achieved[r] for r in rates[-2:])
    body.append(f'<line class="base" x1="{x(limit):.1f}" x2="{x(limit):.1f}" y1="{py1 + 2}" y2="{py0}"/>'
                f'<text class="label" x="{x(limit) - 6:.1f}" y="{py1 + 14}" text-anchor="end">Capacity limit: ~{limit:.0f} req/s</text>')
    body.append(series([(x(r), y(p95[r])) for r in rates], "var(--s2)",
                       f'<text class="label" x="{x(top) + 10:.1f}" y="{y(p95[top]) - 3:.1f}">p95 {p95[top] / 1000:.1f} s</text>'))
    body.append(series([(x(r), y(p50[r])) for r in rates], "var(--s1)",
                       f'<text class="label" x="{x(top) + 10:.1f}" y="{y(p50[top]) + 13:.1f}">p50 {p50[top] / 1000:.1f} s</text>'))
    body.append(legend([("var(--s1)", "p50", 60), ("var(--s2)", "p95", 60)]))
    desc = (f"Write latency stays low at first, then climbs to seconds "
            f"once offered load passes the capacity limit of about {limit:.0f} requests per second.")
    return frame("Write latency jumps once offered load passes capacity",
                 "POST /card-subscriptions, 1 replica, pool 10, 50 ms external latency, median of 3 runs",
                 desc, "".join(body))


def scaling():
    rest = {n: med(runs(f"scale-rest-replicas{n}-run*"), "ok_per_s") for n in (1, 2, 3)}
    kafka = {}
    for n in (1, 2, 3):
        drains = []
        for path in glob.glob(os.path.join(RAW, f"kafka-replicas{n}-partitions12-run*.json")):
            with open(path, encoding="utf8") as f:
                d = json.load(f)
            drains.append(d["drain_seconds"])
        kafka[n] = d["events"] / statistics.median(drains)
    rest_x = {n: rest[n] / rest[1] for n in rest}
    kafka_x = {n: kafka[n] / kafka[1] for n in kafka}
    px0, px1, py0, py1 = LEFT, W - RIGHT, H - BOTTOM, TOP
    x = lambda v: px0 + (v - 1) / 2 * (px1 - px0)
    y = lambda v: py0 - (v - 1) / 2 * (py0 - py1)
    body = []
    for tick in (1, 1.5, 2, 2.5, 3):
        cls = "base" if tick == 1 else "grid"
        body.append(f'<line class="{cls}" x1="{px0}" x2="{px1}" y1="{y(tick):.1f}" y2="{y(tick):.1f}"/>'
                    f'<text class="tick" x="{px0 - 10}" y="{y(tick) + 4:.1f}" text-anchor="end">{tick:.1f}x</text>')
    for tick in (1, 2, 3):
        body.append(f'<text class="tick" x="{x(tick):.1f}" y="{py0 + 20}" text-anchor="middle">{tick}</text>')
    body.append(f'<text class="axis-title" x="{(px0 + px1) / 2}" y="{H - 10}" text-anchor="middle">Replicas</text>')
    body.append(f'<text class="axis-title" x="{px0 - 42}" y="{py1 - 8}">Throughput relative to 1 replica</text>')
    body.append(f'<polyline class="line" points="{x(1):.1f},{y(1):.1f} {x(3):.1f},{y(3):.1f}" '
                f'style="stroke:var(--ref);stroke-width:1.5"/>'
                f'<text class="label" x="{x(3) + 10:.1f}" y="{y(3) + 14:.1f}">Linear 3.00x</text>')
    body.append(series([(x(n), y(rest_x[n])) for n in rest], "var(--s1)",
                       f'<text class="label" x="{x(3) + 10:.1f}" y="{y(rest_x[3]) - 6:.1f}">REST {rest_x[3]:.2f}x</text>'))
    body.append(series([(x(n), y(kafka_x[n])) for n in kafka], "var(--s2)",
                       f'<text class="label" x="{x(3) + 10:.1f}" y="{y(kafka_x[3]) + 4:.1f}">Kafka {kafka_x[3]:.2f}x</text>'))
    body.append(legend([("var(--s1)", f"REST writes (1 replica = {rest[1]:.0f} req/s)", 240),
                        ("var(--s2)", f"Kafka events (1 replica = {kafka[1]:.1f} events/s)", 250)]))
    desc = (f"With three replicas, REST writes reach {rest_x[3]:.2f} times and Kafka processing "
            f"{kafka_x[3]:.2f} times the single-replica throughput, against 3.00 times for linear scaling.")
    return frame(f"REST scales about linearly; Kafka reaches {kafka_x[3]:.2f}x at 3 replicas",
                 "Medians of 3 runs. 12 partitions and 4 listener threads per replica.",
                 desc, "".join(body))


def main():
    charts = os.path.join(RESULTS, "charts")
    os.makedirs(charts, exist_ok=True)
    for name, render in (("write-latency.svg", write_latency), ("scaling.svg", scaling)):
        with open(os.path.join(charts, name), "w", encoding="utf8", newline="\n") as f:
            f.write(render())


if __name__ == "__main__":
    main()
