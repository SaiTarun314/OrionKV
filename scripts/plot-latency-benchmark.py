#!/usr/bin/env python3
import csv
import html
import math
import sys
from collections import defaultdict
from pathlib import Path


def load_rows(path: Path):
    with path.open(newline="") as handle:
        return list(csv.DictReader(handle))


def grouped(rows):
    groups = defaultdict(list)
    for row in rows:
        groups[row["operation"]].append(row)
    return groups


def sort_key(row):
    mode = row["mode"]
    if mode == "replication":
        return int(row["replication_factor"])
    setting = row["setting"]
    if setting.startswith("W") and "-R" in setting:
        left, right = setting.split("-R", 1)
        return (int(left[1:]), int(right))
    return setting


def x_label(row):
    return row["setting"]


def make_chart(rows, operation, metric):
    width = 900
    height = 320
    margin_left = 70
    margin_right = 20
    margin_top = 30
    margin_bottom = 60
    inner_w = width - margin_left - margin_right
    inner_h = height - margin_top - margin_bottom

    values = [float(r[metric]) for r in rows]
    max_value = max(values) if values else 1.0
    max_value = max_value * 1.1 if max_value > 0 else 1.0
    if max_value == 0:
        max_value = 1.0

    xs = []
    ys = []
    labels = []
    for idx, row in enumerate(rows):
        x = margin_left if len(rows) == 1 else margin_left + (inner_w * idx / (len(rows) - 1))
        y = margin_top + inner_h - (float(row[metric]) / max_value) * inner_h
        xs.append(x)
        ys.append(y)
        labels.append(x_label(row))

    points = " ".join(f"{x:.2f},{y:.2f}" for x, y in zip(xs, ys))
    ticks = []
    for i in range(6):
        value = max_value * i / 5
        y = margin_top + inner_h - (value / max_value) * inner_h
        ticks.append((y, value))

    svg = [
        f'<svg viewBox="0 0 {width} {height}" width="{width}" height="{height}" xmlns="http://www.w3.org/2000/svg">',
        '<style>',
        '.axis{stroke:#444;stroke-width:1.5;}'
        '.grid{stroke:#d8d8d8;stroke-width:1;stroke-dasharray:4 4;}'
        '.line{fill:none;stroke:#0b6e4f;stroke-width:3;}'
        '.dot{fill:#c84c09;}'
        '.label{font:12px sans-serif;fill:#222;}'
        '.title{font:16px sans-serif;font-weight:700;fill:#111;}',
        '</style>',
        f'<text class="title" x="{width/2:.0f}" y="20" text-anchor="middle">{html.escape(operation.upper())} {html.escape(metric)}</text>',
    ]

    for y, value in ticks:
        svg.append(f'<line class="grid" x1="{margin_left}" y1="{y:.2f}" x2="{width - margin_right}" y2="{y:.2f}"/>')
        svg.append(f'<text class="label" x="{margin_left - 8}" y="{y + 4:.2f}" text-anchor="end">{value:.1f}</text>')

    svg.append(f'<line class="axis" x1="{margin_left}" y1="{margin_top}" x2="{margin_left}" y2="{margin_top + inner_h}"/>')
    svg.append(f'<line class="axis" x1="{margin_left}" y1="{margin_top + inner_h}" x2="{width - margin_right}" y2="{margin_top + inner_h}"/>')
    svg.append(f'<polyline class="line" points="{points}"/>')

    for x, y, label, row in zip(xs, ys, labels, rows):
        svg.append(f'<circle class="dot" cx="{x:.2f}" cy="{y:.2f}" r="4"/>')
        svg.append(
            f'<text class="label" x="{x:.2f}" y="{height - 18}" text-anchor="middle">{html.escape(label)}</text>'
        )
        svg.append(
            f'<text class="label" x="{x:.2f}" y="{y - 10:.2f}" text-anchor="middle">{float(row[metric]):.2f}</text>'
        )

    svg.append('</svg>')
    return "\n".join(svg)


def make_table(rows):
    headers = [
        "mode", "setting", "replication_factor", "write_quorum", "read_quorum",
        "operation", "sample_count", "avg_latency_ms", "p50_latency_ms", "p95_latency_ms", "p99_latency_ms"
    ]
    out = [
        '<table>',
        '<thead><tr>' + "".join(f"<th>{html.escape(h)}</th>" for h in headers) + "</tr></thead>",
        "<tbody>",
    ]
    for row in rows:
        out.append("<tr>" + "".join(f"<td>{html.escape(row[h])}</td>" for h in headers) + "</tr>")
    out.append("</tbody></table>")
    return "\n".join(out)


def render(summary_csv: Path, output_html: Path):
    rows = load_rows(summary_csv)
    rows.sort(key=lambda row: (row["operation"], sort_key(row)))
    by_operation = grouped(rows)

    parts = [
        "<!DOCTYPE html>",
        "<html><head><meta charset='utf-8'><title>Latency Benchmark Report</title>",
        "<style>",
        "body{font-family:Arial,sans-serif;margin:24px;color:#111;}",
        "h1,h2{margin:0 0 12px 0;}",
        ".chart{margin:18px 0 32px 0;padding:12px;border:1px solid #ddd;border-radius:10px;background:#fff;}",
        "table{border-collapse:collapse;width:100%;margin-top:24px;}",
        "th,td{border:1px solid #ddd;padding:8px 10px;font-size:13px;text-align:left;}",
        "th{background:#f3f3f3;}",
        ".meta{margin-bottom:18px;color:#444;}",
        "</style></head><body>",
        "<h1>Latency Benchmark Report</h1>",
        f"<div class='meta'>Source summary: {html.escape(str(summary_csv))}</div>",
    ]

    for operation in ("put", "get"):
        op_rows = by_operation.get(operation, [])
        if not op_rows:
            continue
        parts.append(f"<h2>{html.escape(operation.upper())}</h2>")
        for metric in ("avg_latency_ms", "p95_latency_ms", "p99_latency_ms"):
            parts.append(f"<div class='chart'>{make_chart(op_rows, operation, metric)}</div>")

    parts.append("<h2>Summary Table</h2>")
    parts.append(make_table(rows))
    parts.append("</body></html>")
    output_html.write_text("\n".join(parts), encoding="utf-8")


def main():
    if len(sys.argv) != 3:
        print("Usage: plot-latency-benchmark.py <summary.csv> <report.html>", file=sys.stderr)
        sys.exit(1)
    render(Path(sys.argv[1]), Path(sys.argv[2]))


if __name__ == "__main__":
    main()
