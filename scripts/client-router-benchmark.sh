#!/usr/bin/env bash
set -euo pipefail

CLIENT_URL="${CLIENT_URL:-http://127.0.0.1:8090}"
TOTAL_REQUESTS="${TOTAL_REQUESTS:-1000}"
CONCURRENCY="${CONCURRENCY:-32}"
KEY_SPACE="${KEY_SPACE:-10000}"
KEY_START="${KEY_START:-1}"
KEY_END="${KEY_END:-}"
KEY_PREFIX="${KEY_PREFIX:-bench-key}"
VALUE_PREFIX="${VALUE_PREFIX:-bench-value}"
WARMUP_REQUESTS="${WARMUP_REQUESTS:-50}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-5}"
REQUEST_SEED="${REQUEST_SEED:-42}"
VERIFY_GETS="${VERIFY_GETS:-false}"
EXPECTED_REPLICATION_FACTOR="${EXPECTED_REPLICATION_FACTOR:-3}"
CSV_PATH="${CSV_PATH:-}"
GRAPH_PATH="${GRAPH_PATH:-client-router-benchmark-replica-distribution.png}"

if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 is required"
  exit 1
fi

python3 - "$CLIENT_URL" "$TOTAL_REQUESTS" "$CONCURRENCY" "$KEY_SPACE" "$KEY_START" "$KEY_END" "$KEY_PREFIX" \
  "$VALUE_PREFIX" "$WARMUP_REQUESTS" "$TIMEOUT_SECONDS" "$REQUEST_SEED" \
  "$VERIFY_GETS" "$EXPECTED_REPLICATION_FACTOR" "$CSV_PATH" "$GRAPH_PATH" <<'PY'
import csv
import json
import math
import random
import statistics
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed

client_url = sys.argv[1].rstrip("/")
total_requests = int(sys.argv[2])
concurrency = int(sys.argv[3])
key_space = int(sys.argv[4])
key_start = int(sys.argv[5])
key_end_arg = sys.argv[6]
key_prefix = sys.argv[7]
value_prefix = sys.argv[8]
warmup_requests = int(sys.argv[9])
timeout_seconds = float(sys.argv[10])
request_seed = int(sys.argv[11])
verify_gets = sys.argv[12].lower() == "true"
expected_replication_factor = int(sys.argv[13])
csv_path = sys.argv[14]
graph_path = sys.argv[15]

key_end = int(key_end_arg) if key_end_arg else key_start + key_space - 1

if total_requests < 1:
    raise SystemExit("TOTAL_REQUESTS must be >= 1")
if concurrency < 1:
    raise SystemExit("CONCURRENCY must be >= 1")
if key_space < 1:
    raise SystemExit("KEY_SPACE must be >= 1")
if key_start < 1:
    raise SystemExit("KEY_START must be >= 1")
if key_end < key_start:
    raise SystemExit("KEY_END must be >= KEY_START")

effective_key_space = key_end - key_start + 1
print_lock = threading.Lock()

def request_json(method, path, body=None):
    url = f"{client_url}{path}"
    data = None
    headers = {"Accept": "application/json"}
    if body is not None:
        data = json.dumps(body, separators=(",", ":")).encode("utf-8")
        headers["Content-Type"] = "application/json"

    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
        payload = response.read().decode("utf-8")
        return response.status, json.loads(payload) if payload else {}

def percentile(values, pct):
    if not values:
        return None
    values = sorted(values)
    if len(values) == 1:
        return values[0]
    rank = (pct / 100.0) * (len(values) - 1)
    lower = math.floor(rank)
    upper = math.ceil(rank)
    if lower == upper:
        return values[int(rank)]
    return values[lower] + (values[upper] - values[lower]) * (rank - lower)

def fmt_ms(value):
    return "n/a" if value is None else f"{value:.2f}ms"

def fmt_num(value):
    return f"{value:,}"

def section(title):
    print("")
    print(f"== {title} ==")

def kv(label, value):
    print(f"  {label:<34} {value}")

def table(headers, rows):
    if not rows:
        print("  none")
        return
    widths = [len(header) for header in headers]
    for row in rows:
        for index, cell in enumerate(row):
            widths[index] = max(widths[index], len(str(cell)))
    header_line = "  " + "  ".join(str(header).ljust(widths[index]) for index, header in enumerate(headers))
    sep_line = "  " + "  ".join("-" * width for width in widths)
    print(header_line)
    print(sep_line)
    for row in rows:
        print("  " + "  ".join(str(cell).ljust(widths[index]) for index, cell in enumerate(row)))

def safe_node(response):
    return response.get("contactedNodeId") or "unknown"

def safe_replicas(response):
    replicas = response.get("replicaNodeIds")
    return replicas if isinstance(replicas, list) else []

def build_workload(count, seed_offset):
    rng = random.Random(request_seed + seed_offset)
    workload = []
    put_slots = max(1, concurrency // 2)
    for index in range(1, count + 1):
        slot = (index - 1) % concurrency
        is_put = slot < put_slots
        key_index = rng.randint(key_start, key_end)
        key = f"{key_prefix}-{key_index}"
        workload.append((index, "PUT" if is_put else "GET", key, key_index))
    return workload

def do_operation(item, warmup=False):
    index, operation, key, key_index = item
    started = time.perf_counter()
    status_code = None
    response = {}
    error = None

    try:
        if operation == "PUT":
            status_code, response = request_json(
                "PUT",
                f"/client/kv/{urllib.parse.quote(key, safe='')}",
                {
                    "value": f"{value_prefix}-{key_index}-{index}",
                    "timestamp": int(time.time() * 1000) + index,
                },
            )
            success = bool(response.get("success"))
        else:
            status_code, response = request_json(
                "GET",
                f"/client/kv/{urllib.parse.quote(key, safe='')}",
            )
            success = (not verify_gets) or bool(response.get("found"))
    except urllib.error.HTTPError as exc:
        status_code = exc.code
        try:
            raw = exc.read().decode("utf-8")
            response = json.loads(raw) if raw else {}
        except Exception:
            response = {}
        error = response.get("message") or response.get("error") or str(exc)
        success = False
    except Exception as exc:
        error = str(exc)
        success = False

    elapsed_ms = (time.perf_counter() - started) * 1000.0
    return {
        "warmup": warmup,
        "index": index,
        "operation": operation,
        "key": key,
        "success": success,
        "status_code": status_code,
        "latency_ms": elapsed_ms,
        "contacted_node": safe_node(response),
        "replicas": safe_replicas(response),
        "error": error,
    }

def run_workload(workload, warmup=False):
    results = []
    with ThreadPoolExecutor(max_workers=concurrency) as executor:
        futures = [executor.submit(do_operation, item, warmup) for item in workload]
        for completed, future in enumerate(as_completed(futures), start=1):
            result = future.result()
            results.append(result)
            if not warmup and (completed % max(1, total_requests // 10) == 0):
                with print_lock:
                    print(f"progress: {completed}/{total_requests}")
    return results

def summarize(results):
    successful = [item for item in results if item["success"]]
    failed = [item for item in results if not item["success"]]
    latencies = [item["latency_ms"] for item in successful]
    by_operation = defaultdict(list)
    replication_by_operation = defaultdict(list)
    for item in successful:
        by_operation[item["operation"]].append(item["latency_ms"])
        replication_by_operation[item["operation"]].append(len(item["replicas"]))

    contacted = Counter(item["contacted_node"] for item in successful)
    replica_hits = Counter()
    replica_counts = [len(item["replicas"]) for item in successful]
    replication_distribution = Counter(replica_counts)
    replication_mismatches = [
        item for item in successful
        if len(item["replicas"]) != expected_replication_factor
    ]
    for item in successful:
        replicas = item["replicas"]
        for replica in replicas:
            replica_hits[replica] += 1

    duration_seconds = max((item.get("completed_at", 0) for item in results), default=0)
    return (
        successful,
        failed,
        latencies,
        by_operation,
        replication_by_operation,
        contacted,
        replica_hits,
        replica_counts,
        replication_distribution,
        replication_mismatches,
        duration_seconds,
    )

def render_replica_distribution_chart(replica_hits):
    if not replica_hits:
        return "skipped: no replica participation data"

    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except Exception as exc:
        return f"skipped: matplotlib unavailable ({exc})"

    def node_sort_key(node):
        try:
            return int(str(node).split("-")[-1])
        except Exception:
            return float("inf")

    nodes = sorted(replica_hits.keys(), key=node_sort_key)
    counts = [replica_hits[node] for node in nodes]

    fig_width = max(10, min(22, 0.45 * len(nodes) + 6))
    fig, ax = plt.subplots(figsize=(fig_width, 6))
    bars = ax.bar(nodes, counts, color="#2563eb", edgecolor="#1d4ed8")
    ax.set_title("Requested KV Pairs per Replica Node")
    ax.set_xlabel("Node")
    ax.set_ylabel("replica responses")
    ax.grid(axis="y", linestyle="--", alpha=0.35)
    ax.set_axisbelow(True)
    plt.xticks(rotation=45, ha="right")

    for bar, count in zip(bars, counts):
        ax.text(
            bar.get_x() + bar.get_width() / 2,
            bar.get_height(),
            str(count),
            ha="center",
            va="bottom",
            fontsize=8,
        )

    fig.tight_layout()
    fig.savefig(graph_path, dpi=180)
    plt.close(fig)
    return graph_path

print("")
print("============================================================")
print("                 OrionKV Client Router Benchmark            ")
print("============================================================")
section("Configuration")
kv("Client URL", client_url)
kv("Total Requests", fmt_num(total_requests))
kv("Concurrency", fmt_num(concurrency))
kv("Key Range", f"{fmt_num(key_start)}-{fmt_num(key_end)} ({fmt_num(effective_key_space)} keys)")
kv("Operation Mix", "half PUT / half GET per concurrency wave")
kv("Warmup Requests", fmt_num(warmup_requests))
kv("Verify GET Found", str(verify_gets))
kv("Expected Replication Factor", expected_replication_factor)
kv("Graph Output", graph_path)

try:
    _, nodes = request_json("GET", "/client/nodes")
    alive = [node for node in nodes.get("nodes", []) if node.get("status") == "ALIVE"]
    section("Router Table")
    kv("Total Nodes", fmt_num(len(nodes.get("nodes", []))))
    kv("Alive Nodes", fmt_num(len(alive)))
    preview_rows = [
        [node.get("nodeId"), node.get("grpcAddress"), node.get("status")]
        for node in alive[:10]
    ]
    table(["Node", "gRPC Address", "Status"], preview_rows)
    if len(alive) > 10:
        print(f"  ... {fmt_num(len(alive) - 10)} more alive nodes")
except Exception as exc:
    section("Router Table")
    kv("Status", f"could not read /client/nodes: {exc}")

if warmup_requests > 0:
    section("Warmup")
    kv("Requests", fmt_num(warmup_requests))
    run_workload(build_workload(warmup_requests, 10_000), warmup=True)
    kv("Status", "complete")

workload = build_workload(total_requests, 0)
started = time.perf_counter()
results = run_workload(workload, warmup=False)
duration_seconds = time.perf_counter() - started
for item in results:
    item["completed_at"] = duration_seconds

(
    successful,
    failed,
    latencies,
    by_operation,
    replication_by_operation,
    contacted,
    replica_hits,
    replica_counts,
    replication_distribution,
    replication_mismatches,
    _,
) = summarize(results)
throughput = len(successful) / duration_seconds if duration_seconds > 0 else 0.0
error_rate = (len(failed) / len(results)) * 100.0 if results else 0.0
avg_replication_factor = statistics.mean(replica_counts) if replica_counts else None
replication_match_rate = (
    ((len(successful) - len(replication_mismatches)) / len(successful)) * 100.0
    if successful else 0.0
)

section("Summary")
table(
    ["Metric", "Value"],
    [
        ["Duration", f"{duration_seconds:.2f}s"],
        ["Successful", fmt_num(len(successful))],
        ["Failed", fmt_num(len(failed))],
        ["Error Rate", f"{error_rate:.2f}%"],
        ["Throughput", f"{throughput:.2f} successful req/s"],
        ["Latency Avg", fmt_ms(statistics.mean(latencies) if latencies else None)],
        ["Latency P50", fmt_ms(percentile(latencies, 50))],
        ["Latency P90", fmt_ms(percentile(latencies, 90))],
        ["Latency P95", fmt_ms(percentile(latencies, 95))],
        ["Latency P99", fmt_ms(percentile(latencies, 99))],
        ["Latency Max", fmt_ms(max(latencies) if latencies else None)],
        ["Avg Replication Factor", "n/a" if avg_replication_factor is None else f"{avg_replication_factor:.2f}"],
        ["Replication Match Rate", f"{replication_match_rate:.2f}%"],
    ],
)

section("By Operation")
operation_rows = []
for operation in ["PUT", "GET"]:
    values = by_operation.get(operation, [])
    replication_values = replication_by_operation.get(operation, [])
    count = len(values)
    op_throughput = count / duration_seconds if duration_seconds > 0 else 0.0
    avg_op_replication = statistics.mean(replication_values) if replication_values else None
    operation_rows.append([
        operation,
        fmt_num(count),
        f"{op_throughput:.2f}/s",
        fmt_ms(statistics.mean(values) if values else None),
        fmt_ms(percentile(values, 95)),
        fmt_ms(percentile(values, 99)),
        "n/a" if avg_op_replication is None else f"{avg_op_replication:.2f}",
    ])
table(["Op", "Count", "Throughput", "Avg", "P95", "P99", "Avg RF"], operation_rows)

section("Replication Factor Verification")
if replica_counts:
    table(
        ["Metric", "Value"],
        [
            ["Expected RF", expected_replication_factor],
            ["Average RF", f"{statistics.mean(replica_counts):.2f}"],
            ["Min RF", min(replica_counts)],
            ["Max RF", max(replica_counts)],
            ["Exact Expected", f"{fmt_num(len(successful) - len(replication_mismatches))}/{fmt_num(len(successful))} ({replication_match_rate:.2f}%)"],
        ],
    )
    distribution_rows = [
        [f"factor_{factor}", fmt_num(count)]
        for factor, count in sorted(replication_distribution.items())
    ]
    print("")
    table(["Replication Factor", "Responses"], distribution_rows)
else:
    print("  none reported")

if replication_mismatches:
    mismatch_rows = [
        [
            item["operation"],
            item["key"],
            len(item["replicas"]),
            ",".join(item["replicas"]) if item["replicas"] else "none",
        ]
        for item in replication_mismatches[:10]
    ]
    print("")
    print("  mismatch samples")
    table(["Op", "Key", "RF", "Replicas"], mismatch_rows)

section("Coordinator Nodes Contacted")
if contacted:
    total_success = sum(contacted.values())
    coordinator_rows = [
        [node, fmt_num(count), f"{(count / total_success) * 100.0:.2f}%"]
        for node, count in contacted.most_common()
    ]
    table(["Coordinator", "Requests", "Share"], coordinator_rows)
else:
    table(["Coordinator", "Requests", "Share"], [])

section("Replica Participation")
if replica_hits:
    total_replica_hits = sum(replica_hits.values())
    replica_rows = [
        [node, fmt_num(count), f"{(count / total_replica_hits) * 100.0:.2f}%"]
        for node, count in replica_hits.most_common(20)
    ]
    table(["Replica", "Appearances", "Share"], replica_rows)
    if len(replica_hits) > 20:
        print(f"  ... {fmt_num(len(replica_hits) - 20)} more replicas")
else:
    table(["Replica", "Appearances", "Share"], [])

section("Visualization")
kv("Replica Distribution Graph", render_replica_distribution_chart(replica_hits))

if failed:
    section("Failure Samples")
    failure_rows = [
        [
            item["operation"],
            item["key"],
            item["status_code"],
            fmt_ms(item["latency_ms"]),
            item["error"],
        ]
        for item in failed[:10]
    ]
    table(["Op", "Key", "Status", "Latency", "Error"], failure_rows)

if csv_path:
    with open(csv_path, "w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(
            fh,
            fieldnames=[
                "index",
                "operation",
                "key",
                "success",
                "status_code",
                "latency_ms",
                "contacted_node",
                "replication_factor",
                "replicas",
                "error",
            ],
        )
        writer.writeheader()
        for item in results:
            writer.writerow({
                "index": item["index"],
                "operation": item["operation"],
                "key": item["key"],
                "success": item["success"],
                "status_code": item["status_code"],
                "latency_ms": f"{item['latency_ms']:.4f}",
                "contacted_node": item["contacted_node"],
                "replication_factor": len(item["replicas"]),
                "replicas": "|".join(item["replicas"]),
                "error": item["error"] or "",
            })
    print("")
    print(f"CSV written to {csv_path}")
PY
