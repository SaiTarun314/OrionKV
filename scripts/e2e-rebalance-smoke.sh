#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

JAR_PATH="${JAR_PATH:-target/orionkv-0.0.1-SNAPSHOT.jar}"
SEED_GRPC_PORT="${SEED_GRPC_PORT:-9091}"
SEED_HTTP_PORT="${SEED_HTTP_PORT:-8081}"
STARTUP_WAIT_SECS="${STARTUP_WAIT_SECS:-10}"
POST_ADD_WAIT_SECS="${POST_ADD_WAIT_SECS:-12}"
PREJOIN_KEY_COUNT="${PREJOIN_KEY_COUNT:-180}"
POSTJOIN_ROUTE_ATTEMPTS="${POSTJOIN_ROUTE_ATTEMPTS:-400}"
ADDED_NODE_ID="${ADDED_NODE_ID:-7}"
ADDED_HTTP_PORT="${ADDED_HTTP_PORT:-8087}"
ADDED_GRPC_PORT="${ADDED_GRPC_PORT:-9097}"

cleanup() {
  ./scripts/kill-all.sh >/dev/null 2>&1 || true
  if [[ -f "pids/node-${ADDED_NODE_ID}.pid" ]]; then
    local pid
    pid="$(cat "pids/node-${ADDED_NODE_ID}.pid" 2>/dev/null || true)"
    if [[ -n "${pid:-}" ]] && ps -p "$pid" >/dev/null 2>&1; then
      kill "$pid" >/dev/null 2>&1 || true
    fi
    rm -f "pids/node-${ADDED_NODE_ID}.pid"
  fi
}

trap cleanup EXIT

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

coordination_put() {
  local grpc_port="$1"
  local request_id="$2"
  local key="$3"
  local value="$4"
  local timestamp="$5"

  grpcurl -plaintext -d "$(jq -nc \
    --arg requestId "$request_id" \
    --arg key "$key" \
    --arg value "$value" \
    --argjson timestamp "$timestamp" \
    '{requestId: $requestId, key: $key, value: $value, timestamp: $timestamp}')" \
    -proto src/main/proto/coordination.proto \
    "127.0.0.1:${grpc_port}" \
    orionkv.node.CoordinationRpc/Put
}

coordination_get() {
  local grpc_port="$1"
  local request_id="$2"
  local key="$3"

  grpcurl -plaintext -d "$(jq -nc \
    --arg requestId "$request_id" \
    --arg key "$key" \
    '{requestId: $requestId, key: $key}')" \
    -proto src/main/proto/coordination.proto \
    "127.0.0.1:${grpc_port}" \
    orionkv.node.CoordinationRpc/Get
}

replica_get() {
  local http_port="$1"
  local key="$2"
  curl -fsS "http://127.0.0.1:${http_port}/internal/replica_get?key=${key}"
}

start_added_node() {
  nohup java -jar "$JAR_PATH" \
    "--server.port=${ADDED_HTTP_PORT}" \
    "--node.node-id=node-${ADDED_NODE_ID}" \
    "--node.address=127.0.0.1:${ADDED_GRPC_PORT}" \
    "--node.seed-address=127.0.0.1:${SEED_GRPC_PORT}" \
    "--node.gossip-interval-ms=1000" \
    "--node.self-heartbeat-interval-ms=500" \
    "--node.failure-detection-interval-ms=1000" \
    "--node.suspect-timeout-ms=15000" \
    "--node.dead-timeout-ms=45000" \
    "--dataplane.storage.log-path=data/node-${ADDED_NODE_ID}.wal.log" \
    >"logs/node-${ADDED_NODE_ID}.log" 2>&1 &
  echo $! >"pids/node-${ADDED_NODE_ID}.pid"
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

echo "Waiting ${STARTUP_WAIT_SECS}s for initial convergence..."
sleep "$STARTUP_WAIT_SECS"

for port in 8081 8082 8083 8084 8085 8086; do
  wait_for_http "http://127.0.0.1:${port}/api/kv/health-check" 5 || true
done

echo "Checking baseline membership..."
for port in 9091 9093 9096; do
  for node in node-1 node-2 node-3 node-4 node-5 node-6; do
    assert_membership_contains "$port" "$node" "ALIVE"
  done
done

echo "Writing pre-join quorum data..."
for ((i=1; i<=PREJOIN_KEY_COUNT; i++)); do
  key="prejoin-key-${i}"
  value="prejoin-value-${i}"
  timestamp=$((1000 + i))
  response="$(coordination_put "$SEED_GRPC_PORT" "prejoin-${i}" "$key" "$value" "$timestamp")"
  echo "$response" | jq -e '.success == true' >/dev/null
done

echo "Adding node-${ADDED_NODE_ID}..."
start_added_node

echo "Waiting ${POST_ADD_WAIT_SECS}s for join and bootstrap..."
sleep "$POST_ADD_WAIT_SECS"

for port in 9091 9093 9096; do
  assert_membership_contains "$port" "node-${ADDED_NODE_ID}" "ALIVE"
done

echo "Checking that at least one pre-join key was rebalanced onto node-${ADDED_NODE_ID}..."
rebalanced_key=""
rebalanced_value=""

for ((i=1; i<=PREJOIN_KEY_COUNT; i++)); do
  key="prejoin-key-${i}"
  value="prejoin-value-${i}"
  route="$(coordination_get "$SEED_GRPC_PORT" "route-check-${i}" "$key")"

  if ! echo "$route" | jq -e --arg node "node-${ADDED_NODE_ID}" '.replicaNodeIds | index($node) != null' >/dev/null; then
    continue
  fi

  node_copy="$(replica_get "$ADDED_HTTP_PORT" "$key" || true)"
  if [[ -n "$node_copy" ]] && echo "$node_copy" | jq -e --arg value "$value" '.found == true and .value == $value' >/dev/null; then
    rebalanced_key="$key"
    rebalanced_value="$value"
    break
  fi
done

if [[ -z "$rebalanced_key" ]]; then
  echo "Did not find a pre-join key that was both routed to node-${ADDED_NODE_ID} after join and present on that node"
  exit 1
fi

echo "Range rebalance confirmed with key ${rebalanced_key}."

echo "Checking that post-join routing sends new writes to node-${ADDED_NODE_ID}..."
routed_key=""
routed_value=""

for ((i=1; i<=POSTJOIN_ROUTE_ATTEMPTS; i++)); do
  key="postjoin-key-${i}"
  value="postjoin-value-${i}"
  timestamp=$((100000 + i))
  response="$(coordination_put "$SEED_GRPC_PORT" "postjoin-${i}" "$key" "$value" "$timestamp")"

  if ! echo "$response" | jq -e '.success == true' >/dev/null; then
    continue
  fi

  if ! echo "$response" | jq -e --arg node "node-${ADDED_NODE_ID}" '.replicaNodeIds | index($node) != null' >/dev/null; then
    continue
  fi

  node_copy="$(replica_get "$ADDED_HTTP_PORT" "$key" || true)"
  if [[ -n "$node_copy" ]] && echo "$node_copy" | jq -e --arg value "$value" '.found == true and .value == $value' >/dev/null; then
    routed_key="$key"
    routed_value="$value"
    break
  fi
done

if [[ -z "$routed_key" ]]; then
  echo "Did not find a post-join routed write that landed on node-${ADDED_NODE_ID}"
  exit 1
fi

echo "Post-join routing confirmed with key ${routed_key}."
echo "Rebalance smoke test passed."
