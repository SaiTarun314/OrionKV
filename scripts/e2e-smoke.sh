#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

JAR_PATH="${JAR_PATH:-target/orionkv-0.0.1-SNAPSHOT.jar}"
STARTUP_WAIT_SECS="${STARTUP_WAIT_SECS:-10}"
POST_ADD_WAIT_SECS="${POST_ADD_WAIT_SECS:-8}"
POST_KILL_WAIT_SECS="${POST_KILL_WAIT_SECS:-14}"
ADDED_NODE_ID="${ADDED_NODE_ID:-7}"
ADDED_HTTP_PORT="${ADDED_HTTP_PORT:-8087}"
ADDED_GRPC_PORT="${ADDED_GRPC_PORT:-9097}"

cleanup() {
  ./scripts/kill-all.sh >/dev/null 2>&1 || true
  if [[ -f "pids/node-${ADDED_NODE_ID}.pid" ]]; then
    pid="$(cat "pids/node-${ADDED_NODE_ID}.pid" 2>/dev/null || true)"
    if [[ -n "${pid:-}" ]] && ps -p "$pid" >/dev/null 2>&1; then
      kill "$pid" >/dev/null 2>&1 || true
    fi
    rm -f "pids/node-${ADDED_NODE_ID}.pid"
  fi
}

wait_for_http() {
  local url="$1"
  local attempts="${2:-40}"
  for ((i=1; i<=attempts; i++)); do
    if curl -s -o /dev/null "$url"; then
      return 0
    fi
    sleep 1
  done
  return 1
}

membership_json() {
  local port="$1"
  grpcurl -plaintext -d '{}' -proto src/main/proto/controlplane.proto \
    "127.0.0.1:${port}" orionkv.node.ClusterRpc/GetMembership
}

assert_membership_contains() {
  local port="$1"
  local node_id="$2"
  local expected_status="$3"
  local json
  json="$(membership_json "$port")"
  echo "$json" | jq -e --arg node_id "$node_id" --arg status "$expected_status" \
    '.membership[] | select(.nodeId == $node_id and .status == $status)' >/dev/null
}

echo "Cleaning old runtime state..."
cleanup
rm -f data/node-*.wal.log

if [[ ! -f "$JAR_PATH" ]]; then
  echo "Missing jar at $JAR_PATH"
  exit 1
fi

echo "Starting baseline cluster..."
./scripts/start-cluster.sh

echo "Waiting ${STARTUP_WAIT_SECS}s for gossip convergence..."
sleep "$STARTUP_WAIT_SECS"

for port in 8081 8082 8083 8084 8085 8086; do
  wait_for_http "http://127.0.0.1:${port}/api/kv/health-check" 5 || true
done

echo "Checking initial membership..."
for port in 9091 9092 9093; do
  for node in node-1 node-2 node-3 node-4 node-5 node-6; do
    assert_membership_contains "$port" "$node" "ALIVE"
  done
done

echo "Exercising KV path..."
curl -fsS -X PUT "http://127.0.0.1:8081/api/kv/e2e-key" \
  -H 'Content-Type: application/json' \
  -d '{"value":"alpha","timestamp":1000}' >/dev/null

curl -fsS "http://127.0.0.1:8081/api/kv/e2e-key" | jq -e '.value == "alpha"' >/dev/null
curl -fsS "http://127.0.0.1:8083/api/kv/e2e-key" | jq -e '.value == "alpha"' >/dev/null

curl -fsS -X DELETE "http://127.0.0.1:8081/api/kv/e2e-key?timestamp=2000" >/dev/null
status_code="$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:8081/api/kv/e2e-key")"
if [[ "$status_code" != "404" ]]; then
  echo "Expected deleted key to return 404, got $status_code"
  exit 1
fi

echo "Adding node-${ADDED_NODE_ID}..."
nohup java -jar "$JAR_PATH" \
  "--server.port=${ADDED_HTTP_PORT}" \
  "--node.node-id=node-${ADDED_NODE_ID}" \
  "--node.address=127.0.0.1:${ADDED_GRPC_PORT}" \
  "--node.seed-address=127.0.0.1:9091" \
  "--node.gossip-interval-ms=1000" \
  "--node.self-heartbeat-interval-ms=500" \
  "--node.failure-detection-interval-ms=1000" \
  "--node.suspect-timeout-ms=15000" \
  "--node.dead-timeout-ms=45000" \
  "--dataplane.storage.log-path=data/node-${ADDED_NODE_ID}.wal.log" \
  >"logs/node-${ADDED_NODE_ID}.log" 2>&1 &
echo $! >"pids/node-${ADDED_NODE_ID}.pid"

echo "Waiting ${POST_ADD_WAIT_SECS}s for join convergence..."
sleep "$POST_ADD_WAIT_SECS"

for port in 9091 9093 9096; do
  assert_membership_contains "$port" "node-${ADDED_NODE_ID}" "ALIVE"
done

echo "Killing node-5 to verify gossip propagation..."
kill "$(cat pids/node-5.pid)"
rm -f pids/node-5.pid

echo "Waiting ${POST_KILL_WAIT_SECS}s for failure detection..."
sleep "$POST_KILL_WAIT_SECS"

for port in 9091 9093 9096; do
  assert_membership_contains "$port" "node-5" "DEAD"
done

echo "Smoke test passed."
cleanup
