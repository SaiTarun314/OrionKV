#!/usr/bin/env python3
import csv
import html
import math
import sys
from collections import defaultdict
from pathlib import Path


METRICS = [
    ("avg_latency_ms", "Average Latency"),
    ("p95_latency_ms", "P95 Latency"),
    ("p99_latency_ms", "P99 Latency"),
]

SERIES = [
    ("put", "#0f766e", "PUT"),
    ("get", "#b45309", "GET"),
]


def load_rows(path: Path):
    with path.open(newline="") as handle:
        return list(csv.DictReader(handle))


def sort_key(row):
    if row["mode"] == "replication":
        return int(row["replication_factor"])
    setting = row["setting"]
    if setting.startswith("W") and "-R" in setting:
        left, right = setting.split("-R", 1)
        return (int(left[1:]), int(right))
    return setting


def label_key(row):
    return row["setting"]


def group_by_mode(rows):
    grouped = defaultdict(list)
    for row in rows:
        grouped[row["mode"]].append(row)
    for mode_rows in grouped.values():
        mode_rows.sort(key=sort_key)
    return grouped


def rows_by_operation(rows):
    grouped = defaultdict(dict)
    for row in rows:
        grouped[row["operation"]][label_key(row)] = row
    return grouped


def compute_mode_title(mode: str) -> str:
    if mode == "quorum":
        return "Latency vs Quorum"
    if mode == "replication":
        return "Latency vs Replication Factor"
    if mode == "quorum-theoretical":
        return "Latency vs Quorum (Theoretical)"
    if mode == "replication-theoretical":
        return "Latency vs Replication Factor (Theoretical)"
    return mode.title()


def compute_mode_subtitle(rows):
    if not rows:
        return ""
    rf = sorted({row["replication_factor"] for row in rows})
    w = sorted({row["write_quorum"] for row in rows})
    r = sorted({row["read_quorum"] for row in rows})
    labels = sorted({label_key(row) for row in rows}, key=lambda x: x)
    return (
        f"Settings: {', '.join(labels)} | "
        f"Replication factors: {', '.join(rf)} | "
        f"Write quorums: {', '.join(w)} | "
        f"Read quorums: {', '.join(r)}"
    )


def format_axis_tick(value):
    if abs(value - round(value)) < 1e-9:
        return f"{int(round(value))}"
    if value >= 100:
        return f"{value:.0f}"
    if value >= 10:
        return f"{value:.1f}".rstrip("0").rstrip(".")
    return f"{value:.2f}".rstrip("0").rstrip(".")


def nice_number(value, round_result):
    if value <= 0:
        return 1.0
    exponent = math.floor(math.log10(value))
    fraction = value / (10 ** exponent)
    if round_result:
        if fraction < 1.5:
            nice_fraction = 1
        elif fraction < 3:
            nice_fraction = 2
        elif fraction < 7:
            nice_fraction = 5
        else:
            nice_fraction = 10
    else:
        if fraction <= 1:
            nice_fraction = 1
        elif fraction <= 2:
            nice_fraction = 2
        elif fraction <= 5:
            nice_fraction = 5
        else:
            nice_fraction = 10
    return nice_fraction * (10 ** exponent)


def make_line_chart(rows, operation, metric_key, metric_label):
    labels = []
    op_rows = rows_by_operation(rows)
    for row in rows:
        label = label_key(row)
        if label not in labels:
            labels.append(label)

    color = next(series_color for op, series_color, _ in SERIES if op == operation)
    operation_label = next(series_label for op, _, series_label in SERIES if op == operation)
    mode_name = rows[0]["mode"] if rows else ""
    if "replication" in mode_name:
        x_axis_label = "Replication Configuration"
    else:
        x_axis_label = "Quorum Configuration"

    values = []
    for label in labels:
        row = op_rows.get(operation, {}).get(label)
        if row is not None:
            values.append(float(row[metric_key]))

    max_value = max(values) if values else 1.0
    max_value = max(max_value, 1.0)

    width = 980
    height = 468
    margin_left = 96
    margin_right = 28
    margin_top = 82
    margin_bottom = 84
    inner_w = width - margin_left - margin_right
    inner_h = height - margin_top - margin_bottom

    def x_pos(idx):
        if len(labels) == 1:
            return margin_left + inner_w / 2
        return margin_left + (inner_w * idx / (len(labels) - 1))

    def y_pos(value):
        return margin_top + inner_h - ((value / axis_max) * inner_h)

    tick_count = 5
    tick_step = nice_number(max_value / (tick_count - 1), round_result=True)
    axis_max = tick_step * (tick_count - 1)
    if axis_max < max_value:
        axis_max = tick_step * tick_count
    ticks = []
    steps = int(round(axis_max / tick_step))
    for i in range(steps + 1):
        value = tick_step * i
        ticks.append((y_pos(value), value))

    svg = [
        f'<svg viewBox="0 0 {width} {height}" width="100%" height="100%" xmlns="http://www.w3.org/2000/svg">',
        "<style>",
        ".axis{stroke:#334155;stroke-width:1.4;}",
        ".grid{stroke:#cbd5e1;stroke-width:1;stroke-dasharray:5 5;}",
        ".tick{font:12px ui-sans-serif,system-ui,sans-serif;fill:#475569;}",
        ".title{font:700 18px ui-sans-serif,system-ui,sans-serif;fill:#0f172a;}",
        ".subtitle{font:12px ui-sans-serif,system-ui,sans-serif;fill:#64748b;}",
        ".xlabel{font:12px ui-sans-serif,system-ui,sans-serif;fill:#334155;}",
        ".axislabel{font:13px ui-sans-serif,system-ui,sans-serif;font-weight:600;fill:#334155;}",
        ".legend{font:12px ui-sans-serif,system-ui,sans-serif;fill:#0f172a;}",
        ".point-label{font:11px ui-sans-serif,system-ui,sans-serif;fill:#0f172a;}",
        ".point-label-bg{fill:#ffffff;stroke:#cbd5e1;stroke-width:1;rx:4;ry:4;}",
        "</style>",
        f'<text class="title" x="{width / 2:.0f}" y="26" text-anchor="middle">{html.escape(operation_label)} {html.escape(metric_label)}</text>',
        f'<text class="subtitle" x="{width / 2:.0f}" y="46" text-anchor="middle">Lower is better. Values in milliseconds.</text>',
    ]

    for y, value in ticks:
        svg.append(f'<line class="grid" x1="{margin_left}" y1="{y:.2f}" x2="{width - margin_right}" y2="{y:.2f}"/>')
        svg.append(
            f'<text class="tick" x="{margin_left - 10}" y="{y + 4:.2f}" text-anchor="end">{html.escape(format_axis_tick(value))}</text>'
        )

    svg.append(f'<line class="axis" x1="{margin_left}" y1="{margin_top}" x2="{margin_left}" y2="{margin_top + inner_h}"/>')
    svg.append(f'<line class="axis" x1="{margin_left}" y1="{margin_top + inner_h}" x2="{width - margin_right}" y2="{margin_top + inner_h}"/>')
    svg.append(
        f'<text class="axislabel" x="{(margin_left + width - margin_right) / 2:.2f}" y="{height - 6}" text-anchor="middle">{html.escape(x_axis_label)}</text>'
    )
    svg.append(
        f'<text class="axislabel" x="22" y="{margin_top + (inner_h / 2):.2f}" text-anchor="middle" transform="rotate(-90 22 {margin_top + (inner_h / 2):.2f})">Latency (ms)</text>'
    )

    legend_x = width - margin_right - 150
    legend_y = 62
    svg.append(f'<line x1="{legend_x}" y1="{legend_y}" x2="{legend_x + 26}" y2="{legend_y}" stroke="{color}" stroke-width="4"/>')
    svg.append(f'<circle cx="{legend_x + 13}" cy="{legend_y}" r="4" fill="{color}"/>')
    svg.append(f'<text class="legend" x="{legend_x + 36}" y="{legend_y + 4}">{html.escape(operation_label)}</text>')

    for idx, label in enumerate(labels):
        x = x_pos(idx)
        svg.append(f'<line class="grid" x1="{x:.2f}" y1="{margin_top + inner_h}" x2="{x:.2f}" y2="{margin_top + inner_h + 6}"/>')
        svg.append(
            f'<text class="xlabel" x="{x:.2f}" y="{height - 30}" text-anchor="middle">{html.escape(label)}</text>'
        )

    points = []
    point_rows = []
    for idx, label in enumerate(labels):
        row = op_rows.get(operation, {}).get(label)
        if row is None:
            continue
        value = float(row[metric_key])
        x = x_pos(idx)
        y = y_pos(value)
        points.append(f"{x:.2f},{y:.2f}")
        point_rows.append((x, y, value, row))

    if points:
        svg.append(f'<polyline points="{" ".join(points)}" fill="none" stroke="{color}" stroke-width="3.5" stroke-linecap="round" stroke-linejoin="round"/>')

        for x, y, value, row in point_rows:
            tooltip = (
                f'{row["setting"]} | {operation.upper()} | '
                f'avg={row["avg_latency_ms"]}ms | '
                f'p95={row["p95_latency_ms"]}ms | '
                f'p99={row["p99_latency_ms"]}ms | '
                f'n={row["sample_count"]}'
            )
            svg.append(f'<circle cx="{x:.2f}" cy="{y:.2f}" r="5" fill="{color}"><title>{html.escape(tooltip)}</title></circle>')
        for index, (x, y, value, _) in enumerate(point_rows):
            label_text = f"{value:.2f}"
            text_width = max(28, len(label_text) * 7)
            box_width = text_width + 10
            box_height = 18
            if index % 2 == 0:
                label_center_y = y - 18
            else:
                label_center_y = y + 20
            if index == 0:
                label_center_x = x + (box_width / 2) + 8
            elif index == len(point_rows) - 1:
                label_center_x = x - (box_width / 2) - 8
            else:
                shift = (box_width / 2) + 6
                label_center_x = x - shift if index % 3 == 0 else x + shift

            min_center_x = margin_left + (box_width / 2) + 4
            max_center_x = width - margin_right - (box_width / 2) - 4
            min_center_y = margin_top + (box_height / 2) + 4
            max_center_y = margin_top + inner_h - (box_height / 2) - 4
            label_center_x = min(max_center_x, max(min_center_x, label_center_x))
            label_center_y = min(max_center_y, max(min_center_y, label_center_y))

            rect_x = label_center_x - (box_width / 2)
            rect_y = label_center_y - (box_height / 2)
            text_y = label_center_y + 4
            svg.append(
                f'<rect class="point-label-bg" x="{rect_x:.2f}" y="{rect_y:.2f}" width="{box_width:.2f}" height="{box_height:.2f}"/>'
            )
            svg.append(
                f'<text class="point-label" x="{label_center_x:.2f}" y="{text_y:.2f}" text-anchor="middle">{label_text}</text>'
            )

    svg.append("</svg>")
    return "\n".join(svg)


def make_summary_cards(rows):
    cards = []
    grouped = rows_by_operation(rows)
    for op, _, label in SERIES:
        op_rows = list(grouped.get(op, {}).values())
        if not op_rows:
            continue
        avg_of_avg = sum(float(row["avg_latency_ms"]) for row in op_rows) / len(op_rows)
        avg_of_p95 = sum(float(row["p95_latency_ms"]) for row in op_rows) / len(op_rows)
        max_p99 = max(float(row["p99_latency_ms"]) for row in op_rows)
        cards.append(
            "<div class='stat-card'>"
            f"<div class='stat-label'>{html.escape(label)}</div>"
            f"<div class='stat-main'>avg {avg_of_avg:.2f} ms</div>"
            f"<div class='stat-sub'>mean p95 {avg_of_p95:.2f} ms</div>"
            f"<div class='stat-sub'>worst p99 {max_p99:.2f} ms</div>"
            "</div>"
        )
    return "\n".join(cards)


def make_table(rows):
    headers = [
        "mode", "setting", "replication_factor", "write_quorum", "read_quorum",
        "operation", "sample_count", "avg_latency_ms", "p50_latency_ms", "p95_latency_ms", "p99_latency_ms"
    ]
    out = [
        "<table>",
        "<thead><tr>" + "".join(f"<th>{html.escape(header)}</th>" for header in headers) + "</tr></thead>",
        "<tbody>",
    ]
    for row in rows:
        out.append("<tr>" + "".join(f"<td>{html.escape(row[header])}</td>" for header in headers) + "</tr>")
    out.append("</tbody></table>")
    return "\n".join(out)


def render(summary_csv: Path, output_html: Path):
    rows = load_rows(summary_csv)
    rows.sort(key=lambda row: (row["mode"], sort_key(row), row["operation"]))
    by_mode = group_by_mode(rows)

    parts = [
        "<!DOCTYPE html>",
        "<html><head><meta charset='utf-8'><title>Latency Benchmark Report</title>",
        "<style>",
        ":root{--bg:#f8fafc;--card:#ffffff;--ink:#0f172a;--muted:#475569;--line:#e2e8f0;--accent:#0f766e;--accent2:#b45309;}",
        "*{box-sizing:border-box;}",
        "body{margin:0;background:linear-gradient(180deg,#eff6ff 0%,#f8fafc 18%,#f8fafc 100%);color:var(--ink);font-family:ui-sans-serif,system-ui,-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;}",
        ".page{max-width:1180px;margin:0 auto;padding:40px 24px 64px;}",
        ".hero{padding:28px 32px;border:1px solid var(--line);border-radius:24px;background:radial-gradient(circle at top left,#ffffff 0%,#f8fafc 58%,#eef2ff 100%);box-shadow:0 20px 50px rgba(15,23,42,0.08);}",
        ".eyebrow{font-size:12px;font-weight:700;letter-spacing:.12em;text-transform:uppercase;color:#0f766e;margin-bottom:10px;}",
        "h1{margin:0;font-size:34px;line-height:1.1;}",
        ".sub{margin-top:10px;font-size:14px;color:var(--muted);}",
        ".mode-section{margin-top:30px;padding:26px;border:1px solid var(--line);border-radius:24px;background:var(--card);box-shadow:0 12px 36px rgba(15,23,42,0.06);}",
        ".mode-head{display:flex;justify-content:space-between;gap:24px;align-items:flex-start;flex-wrap:wrap;margin-bottom:18px;}",
        ".mode-title{font-size:24px;font-weight:750;margin:0;}",
        ".mode-sub{margin-top:6px;font-size:13px;color:var(--muted);max-width:780px;line-height:1.5;}",
        ".stats{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:14px;margin:18px 0 26px;}",
        ".stat-card{padding:16px 18px;border-radius:18px;background:linear-gradient(180deg,#ffffff 0%,#f8fafc 100%);border:1px solid var(--line);}",
        ".stat-label{font-size:12px;font-weight:700;letter-spacing:.08em;color:var(--muted);text-transform:uppercase;}",
        ".stat-main{margin-top:8px;font-size:24px;font-weight:800;}",
        ".stat-sub{margin-top:4px;font-size:13px;color:var(--muted);}",
        ".chart-grid{display:grid;grid-template-columns:1fr 1fr;gap:18px;}",
        ".chart-card{padding:18px;border:1px solid var(--line);border-radius:20px;background:#fff;}",
        ".chart-wrap{width:100%;overflow-x:auto;}",
        ".chart-pair-title{margin:6px 0 10px;font-size:16px;font-weight:700;color:#0f172a;}",
        "@media (max-width: 980px){.chart-grid{grid-template-columns:1fr;}}",
        ".table-wrap{margin-top:22px;overflow:auto;border:1px solid var(--line);border-radius:18px;}",
        "table{border-collapse:collapse;width:100%;background:#fff;}",
        "th,td{padding:12px 14px;border-bottom:1px solid var(--line);font-size:13px;text-align:left;white-space:nowrap;}",
        "th{background:#f8fafc;color:#334155;font-weight:700;position:sticky;top:0;}",
        "tbody tr:nth-child(even){background:#fcfcfd;}",
        "</style></head><body><div class='page'>",
        "<section class='hero'>",
        "<div class='eyebrow'>OrionKV Benchmark</div>",
        "<h1>Latency Benchmark Report</h1>",
        f"<div class='sub'>Summary source: {html.escape(str(summary_csv))}</div>",
        "</section>",
    ]

    ordered_modes = (
        "quorum",
        "replication",
        "quorum-theoretical",
        "replication-theoretical",
    )
    for mode in ordered_modes:
        mode_rows = by_mode.get(mode, [])
        if not mode_rows:
            continue
        parts.append("<section class='mode-section'>")
        parts.append("<div class='mode-head'>")
        parts.append("<div>")
        parts.append(f"<h2 class='mode-title'>{html.escape(compute_mode_title(mode))}</h2>")
        parts.append(f"<div class='mode-sub'>{html.escape(compute_mode_subtitle(mode_rows))}</div>")
        parts.append("</div>")
        parts.append("</div>")
        parts.append(f"<div class='stats'>{make_summary_cards(mode_rows)}</div>")
        for metric_key, metric_label in METRICS:
            parts.append(f"<div class='chart-pair-title'>{html.escape(metric_label)}</div>")
            parts.append("<div class='chart-grid'>")
            for operation, _, _ in SERIES:
                parts.append("<div class='chart-card'><div class='chart-wrap'>")
                parts.append(make_line_chart(mode_rows, operation, metric_key, metric_label))
                parts.append("</div></div>")
            parts.append("</div>")
        parts.append("<div class='table-wrap'>")
        parts.append(make_table(mode_rows))
        parts.append("</div>")
        parts.append("</section>")

    parts.append("</div></body></html>")
    output_html.write_text("\n".join(parts), encoding="utf-8")


def main():
    if len(sys.argv) != 3:
        print("Usage: plot-latency-benchmark.py <summary.csv> <report.html>", file=sys.stderr)
        sys.exit(1)
    render(Path(sys.argv[1]), Path(sys.argv[2]))


if __name__ == "__main__":
    main()
