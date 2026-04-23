#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$ROOT_DIR"

TOTAL_KEYS="${TOTAL_KEYS:-50000}"
SAMPLE_SIZE="${SAMPLE_SIZE:-500}"
KEY_PREFIX="${KEY_PREFIX:-bulk-key}"
NODE_COUNT="${NODE_COUNT:-25}"
NODE_ID_OFFSET="${NODE_ID_OFFSET:-0}"
COORDINATOR_PORTS="${COORDINATOR_PORTS:-}"
COORDINATOR_HOST="${COORDINATOR_HOST:-127.0.0.1}"
GRPC_PORT_BASE="${GRPC_PORT_BASE:-19090}"
PROTO_FILE="${PROTO_FILE:-src/main/proto/coordination.proto}"
CONTROL_PROTO_FILE="${CONTROL_PROTO_FILE:-src/main/proto/controlplane.proto}"
MEMBERSHIP_HOST="${MEMBERSHIP_HOST:-127.0.0.1}"
MEMBERSHIP_PORT="${MEMBERSHIP_PORT:-$((GRPC_PORT_BASE + NODE_ID_OFFSET + 1))}"
REQUEST_PREFIX="${REQUEST_PREFIX:-audit}"

if ! command -v grpcurl >/dev/null 2>&1; then
  echo "grpcurl is required"
  exit 1
fi

if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 is required"
  exit 1
fi

if ! [[ "$TOTAL_KEYS" =~ ^[0-9]+$ ]] || (( TOTAL_KEYS < 1 )); then
  echo "TOTAL_KEYS must be a positive integer"
  exit 1
fi

if ! [[ "$SAMPLE_SIZE" =~ ^[0-9]+$ ]] || (( SAMPLE_SIZE < 1 )); then
  echo "SAMPLE_SIZE must be a positive integer"
  exit 1
fi

if ! [[ "$NODE_COUNT" =~ ^[0-9]+$ ]] || (( NODE_COUNT < 1 )); then
  echo "NODE_COUNT must be a positive integer"
  exit 1
fi

if ! [[ "$NODE_ID_OFFSET" =~ ^[0-9]+$ ]]; then
  echo "NODE_ID_OFFSET must be a non-negative integer"
  exit 1
fi

if [[ -z "$COORDINATOR_PORTS" ]]; then
  declare -A seen_ports=()
  computed_ports=()
  sample_positions=(1 $(( (NODE_COUNT + 2) / 3 )) $(( (2 * NODE_COUNT + 2) / 3 )) "$NODE_COUNT")
  for position in "${sample_positions[@]}"; do
    if (( position < 1 )); then
      position=1
    fi
    if (( position > NODE_COUNT )); then
      position=$NODE_COUNT
    fi
    port=$((GRPC_PORT_BASE + NODE_ID_OFFSET + position))
    if [[ -z "${seen_ports[$port]:-}" ]]; then
      computed_ports+=("$port")
      seen_ports[$port]=1
    fi
  done
  COORDINATOR_PORTS="${computed_ports[*]}"
fi

sample_keys() {
  python3 - "$TOTAL_KEYS" "$SAMPLE_SIZE" "$KEY_PREFIX" <<'PY'
import sys

total = int(sys.argv[1])
sample = int(sys.argv[2])
prefix = sys.argv[3]

sample = min(sample, total)
seen = set()
if sample == total:
    indices = range(1, total + 1)
else:
    for i in range(sample):
        idx = 1 + ((i * total) // sample)
        if idx > total:
            idx = total
        seen.add(idx)
    indices = sorted(seen)

for idx in indices:
    print(f"{prefix}-{idx}")
PY
}

membership_json="$(grpcurl -plaintext -d '{}' -proto "$CONTROL_PROTO_FILE" \
  "${MEMBERSHIP_HOST}:${MEMBERSHIP_PORT}" orionkv.node.ClusterRpc/GetMembership)"

coord_ports_csv="$(printf '%s\n' $COORDINATOR_PORTS | paste -sd, -)"
keys_csv="$(sample_keys | paste -sd, -)"

if [[ -z "$keys_csv" ]]; then
  echo "No keys selected for audit"
  exit 1
fi

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

printf '%s' "$membership_json" > "${tmp_dir}/membership.json"

python3 - "$TOTAL_KEYS" "$SAMPLE_SIZE" "$KEY_PREFIX" "$coord_ports_csv" "$COORDINATOR_HOST" "$PROTO_FILE" "$REQUEST_PREFIX" "$tmp_dir" <<'PY'
import json
import math
import subprocess
import sys
from collections import Counter, defaultdict

total_keys = int(sys.argv[1])
sample_size = int(sys.argv[2])
key_prefix = sys.argv[3]
coordinator_ports = [p for p in sys.argv[4].split(",") if p]
coordinator_host = sys.argv[5]
proto_file = sys.argv[6]
request_prefix = sys.argv[7]
tmp_dir = sys.argv[8]

with open(f"{tmp_dir}/membership.json", "r", encoding="utf-8") as fh:
    membership = json.load(fh)

members = membership.get("membership", [])
status_counts = Counter(member.get("status", "UNKNOWN") for member in members)
member_addresses = {
    member.get("nodeId"): (member.get("address") or "").replace("http://", "").replace("https://", "")
    for member in members
}

def sample_indices(total: int, sample: int):
    sample = min(sample, total)
    if sample == total:
        return list(range(1, total + 1))
    seen = set()
    for i in range(sample):
        idx = 1 + ((i * total) // sample)
        if idx > total:
            idx = total
        seen.add(idx)
    return sorted(seen)

def grpc_json(target: str, method: str, payload: dict):
    command = [
        "grpcurl",
        "-plaintext",
        "-d",
        json.dumps(payload, separators=(",", ":")),
        "-proto",
        proto_file,
        target,
        method,
    ]
    completed = subprocess.run(command, capture_output=True, text=True)
    if completed.returncode != 0:
        return None, completed.stderr.strip() or completed.stdout.strip()
    return json.loads(completed.stdout), None

def version_tuple(result: dict):
    found = result.get("found", False)
    if not found:
        return None
    return (
        int(result.get("timestamp", -1)),
        1 if result.get("tombstone", result.get("is_deleted", False)) else 0,
        result.get("value") or "",
    )

indices = sample_indices(total_keys, sample_size)
route_mismatches = []
replica_counts = Counter()
per_key_stale = defaultdict(list)
per_key_missing = defaultdict(list)
keys_checked = 0
quorum_failures = 0

for idx in indices:
    key = f"{key_prefix}-{idx}"
    coordinator_routes = {}
    coordinator_responses = {}

    for port in coordinator_ports:
        response, error = grpc_json(
            f"{coordinator_host}:{int(port)}",
            "orionkv.node.CoordinationRpc/Get",
            {"requestId": f"{request_prefix}-{port}-{idx}", "key": key},
        )
        if error is not None:
            coordinator_responses[port] = {"error": error}
            continue
        coordinator_responses[port] = response
        replicas = tuple(response.get("replicaNodeIds", []))
        coordinator_routes[port] = replicas

    successful = {port: route for port, route in coordinator_routes.items()}
    if not successful:
        quorum_failures += 1
        continue

    baseline_port = next(iter(successful))
    baseline_route = successful[baseline_port]
    for port, route in successful.items():
        if route != baseline_route:
            route_mismatches.append({
                "key": key,
                "baseline_port": baseline_port,
                "baseline_route": list(baseline_route),
                "port": port,
                "route": list(route),
            })

    response = coordinator_responses[baseline_port]
    if response.get("message") == "read quorum not met":
        quorum_failures += 1
        continue

    keys_checked += 1
    for replica in baseline_route:
        replica_counts[replica] += 1

    versions = {}
    for replica in baseline_route:
        target = member_addresses.get(replica, "")
        if not target:
            per_key_missing[key].append({"replica": replica, "reason": "missing member address"})
            continue

        replica_response, error = grpc_json(
            target,
            "orionkv.node.ReplicaDataRpc/GetReplica",
            {"requestId": f"{request_prefix}-replica-{idx}", "key": key},
        )
        if error is not None:
            per_key_missing[key].append({"replica": replica, "reason": error})
            continue
        versions[replica] = replica_response

    present_versions = {node: version_tuple(resp) for node, resp in versions.items()}
    found_versions = {node: vt for node, vt in present_versions.items() if vt is not None}

    if not found_versions:
        continue

    winner = max(found_versions.values())
    for node, vt in present_versions.items():
        if vt is None:
            per_key_missing[key].append({"replica": node, "reason": "not found"})
        elif vt != winner:
            per_key_stale[key].append({
                "replica": node,
                "observed": {
                    "timestamp": vt[0],
                    "is_deleted": bool(vt[1]),
                    "value": vt[2],
                },
                "winner": {
                    "timestamp": winner[0],
                    "is_deleted": bool(winner[1]),
                    "value": winner[2],
                },
            })

total_assignments = sum(replica_counts.values())
alive_nodes = [m["nodeId"] for m in members if m.get("status") == "ALIVE"]
alive_counts = [replica_counts.get(node, 0) for node in alive_nodes]
avg = (sum(alive_counts) / len(alive_counts)) if alive_counts else 0.0
variance = (sum((count - avg) ** 2 for count in alive_counts) / len(alive_counts)) if alive_counts else 0.0
stddev = math.sqrt(variance)

print("OrionKV Balance Audit")
print(f"  sampled_keys={len(indices)}")
print(f"  keys_checked={keys_checked}")
print(f"  total_members={len(members)}")
print(f"  alive_members={status_counts.get('ALIVE', 0)}")
print(f"  suspect_members={status_counts.get('SUSPECT', 0)}")
print(f"  dead_members={status_counts.get('DEAD', 0)}")
print(f"  quorum_failures={quorum_failures}")
print(f"  route_mismatches={len(route_mismatches)}")
print(f"  stale_replica_keys={len(per_key_stale)}")
print(f"  missing_replica_keys={len(per_key_missing)}")
print()
print("Replica Distribution Across Alive Nodes")
print(f"  total_replica_assignments={total_assignments}")
print(f"  avg_assignments_per_alive_node={avg:.2f}")
print(f"  stddev={stddev:.2f}")
if alive_nodes:
    ranked = sorted(((node, replica_counts.get(node, 0)) for node in alive_nodes), key=lambda item: (-item[1], item[0]))
    print("  busiest_nodes=" + ", ".join(f"{node}:{count}" for node, count in ranked[:10]))
    print("  least_busy_nodes=" + ", ".join(f"{node}:{count}" for node, count in ranked[-10:]))
print()

def print_examples(title, mapping, limit=10):
    print(title)
    if not mapping:
        print("  none")
        print()
        return
    for idx, (key, entries) in enumerate(mapping.items()):
        if idx >= limit:
            break
        print(f"  {key}")
        for entry in entries[:3]:
            print(f"    {json.dumps(entry, sort_keys=True)}")
    print()

if route_mismatches:
    print("Route mismatch examples")
    for mismatch in route_mismatches[:10]:
        print("  " + json.dumps(mismatch, sort_keys=True))
    print()
else:
    print("Route mismatch examples")
    print("  none")
    print()

print_examples("Stale replica examples", per_key_stale)
print_examples("Missing replica examples", per_key_missing)
PY
