#!/usr/bin/env bash
set -euo pipefail

CLIENT_URL="${CLIENT_URL:-http://127.0.0.1:8090}"
TOTAL_REQUESTS="${TOTAL_REQUESTS:-1000}"
CONCURRENCY="${CONCURRENCY:-32}"
KEY_SPACE="${KEY_SPACE:-10000}"
KEY_PREFIX="${KEY_PREFIX:-bench-key}"
VALUE_PREFIX="${VALUE_PREFIX:-bench-value}"
PUT_PERCENT="${PUT_PERCENT:-50}"
WARMUP_REQUESTS="${WARMUP_REQUESTS:-50}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-5}"
REQUEST_SEED="${REQUEST_SEED:-42}"
VERIFY_GETS="${VERIFY_GETS:-false}"
CSV_PATH="${CSV_PATH:-}"

if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 is required"
  exit 1
fi

python3 - "$CLIENT_URL" "$TOTAL_REQUESTS" "$CONCURRENCY" "$KEY_SPACE" "$KEY_PREFIX" \
  "$VALUE_PREFIX" "$PUT_PERCENT" "$WARMUP_REQUESTS" "$TIMEOUT_SECONDS" "$REQUEST_SEED" \
  "$VERIFY_GETS" "$CSV_PATH" <<'PY'
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
key_prefix = sys.argv[5]
value_prefix = sys.argv[6]
put_percent = int(sys.argv[7])
warmup_requests = int(sys.argv[8])
timeout_seconds = float(sys.argv[9])
request_seed = int(sys.argv[10])
verify_gets = sys.argv[11].lower() == "true"
csv_path = sys.argv[12]

if total_requests < 1:
    raise SystemExit("TOTAL_REQUESTS must be >= 1")
if concurrency < 1:
    raise SystemExit("CONCURRENCY must be >= 1")
if key_space < 1:
    raise SystemExit("KEY_SPACE must be >= 1")
if not 0 <= put_percent <= 100:
    raise SystemExit("PUT_PERCENT must be between 0 and 100")

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

def safe_node(response):
    return response.get("contactedNodeId") or "unknown"

def safe_replicas(response):
    replicas = response.get("replicaNodeIds")
    return replicas if isinstance(replicas, list) else []

def build_workload(count, seed_offset):
    rng = random.Random(request_seed + seed_offset)
    workload = []
    for index in range(1, count + 1):
        is_put = rng.randrange(100) < put_percent
        key_index = rng.randint(1, key_space)
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
    for item in successful:
        by_operation[item["operation"]].append(item["latency_ms"])

    contacted = Counter(item["contacted_node"] for item in successful)
    replica_hits = Counter()
    replica_sets = Counter()
    for item in successful:
        replicas = item["replicas"]
        for replica in replicas:
            replica_hits[replica] += 1
        if replicas:
            replica_sets["|".join(replicas)] += 1

    duration_seconds = max((item.get("completed_at", 0) for item in results), default=0)
    return successful, failed, latencies, by_operation, contacted, replica_hits, replica_sets, duration_seconds

print("Client Router Benchmark")
print(f"  client_url={client_url}")
print(f"  total_requests={total_requests}")
print(f"  concurrency={concurrency}")
print(f"  key_space={key_space}")
print(f"  put_percent={put_percent}")
print(f"  warmup_requests={warmup_requests}")
print(f"  verify_gets={verify_gets}")
print("")

try:
    _, nodes = request_json("GET", "/client/nodes")
    alive = [node for node in nodes.get("nodes", []) if node.get("status") == "ALIVE"]
    print(f"Router table: {len(nodes.get('nodes', []))} nodes, {len(alive)} ALIVE")
    for node in alive[:10]:
        print(f"  {node.get('nodeId')} {node.get('grpcAddress')}")
    if len(alive) > 10:
        print(f"  ... {len(alive) - 10} more")
    print("")
except Exception as exc:
    print(f"Could not read /client/nodes before benchmark: {exc}")
    print("")

if warmup_requests > 0:
    print(f"Warmup: {warmup_requests} requests")
    run_workload(build_workload(warmup_requests, 10_000), warmup=True)
    print("Warmup complete")
    print("")

workload = build_workload(total_requests, 0)
started = time.perf_counter()
results = run_workload(workload, warmup=False)
duration_seconds = time.perf_counter() - started
for item in results:
    item["completed_at"] = duration_seconds

successful, failed, latencies, by_operation, contacted, replica_hits, replica_sets, _ = summarize(results)
throughput = len(successful) / duration_seconds if duration_seconds > 0 else 0.0
error_rate = (len(failed) / len(results)) * 100.0 if results else 0.0

print("")
print("Summary")
print(f"  duration={duration_seconds:.2f}s")
print(f"  successful={len(successful)}")
print(f"  failed={len(failed)}")
print(f"  error_rate={error_rate:.2f}%")
print(f"  throughput={throughput:.2f} successful requests/sec")
print(f"  latency_avg={fmt_ms(statistics.mean(latencies) if latencies else None)}")
print(f"  latency_p50={fmt_ms(percentile(latencies, 50))}")
print(f"  latency_p90={fmt_ms(percentile(latencies, 90))}")
print(f"  latency_p95={fmt_ms(percentile(latencies, 95))}")
print(f"  latency_p99={fmt_ms(percentile(latencies, 99))}")
print(f"  latency_max={fmt_ms(max(latencies) if latencies else None)}")

print("")
print("By Operation")
for operation in ["PUT", "GET"]:
    values = by_operation.get(operation, [])
    count = len(values)
    op_throughput = count / duration_seconds if duration_seconds > 0 else 0.0
    print(
        f"  {operation}: count={count} throughput={op_throughput:.2f}/s "
        f"avg={fmt_ms(statistics.mean(values) if values else None)} "
        f"p95={fmt_ms(percentile(values, 95))} p99={fmt_ms(percentile(values, 99))}"
    )

print("")
print("Coordinator Nodes Contacted")
if contacted:
    total_success = sum(contacted.values())
    for node, count in contacted.most_common():
        print(f"  {node}: {count} ({(count / total_success) * 100.0:.2f}%)")
else:
    print("  none")

print("")
print("Replica Participation")
if replica_hits:
    total_replica_hits = sum(replica_hits.values())
    for node, count in replica_hits.most_common(20):
        print(f"  {node}: {count} ({(count / total_replica_hits) * 100.0:.2f}%)")
    if len(replica_hits) > 20:
        print(f"  ... {len(replica_hits) - 20} more")
else:
    print("  none reported")

print("")
print("Most Common Replica Sets")
if replica_sets:
    for route, count in replica_sets.most_common(10):
        print(f"  {route}: {count}")
else:
    print("  none reported")

if failed:
    print("")
    print("Failure Samples")
    for item in failed[:10]:
        print(
            f"  {item['operation']} {item['key']} status={item['status_code']} "
            f"latency={item['latency_ms']:.2f}ms error={item['error']}"
        )

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
                "replicas": "|".join(item["replicas"]),
                "error": item["error"] or "",
            })
    print("")
    print(f"CSV written to {csv_path}")
PY
