#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

START_SCRIPT="${START_SCRIPT:-$ROOT_DIR/scripts/start-cluster.sh}"
KILL_SCRIPT="${KILL_SCRIPT:-$ROOT_DIR/scripts/kill-all.sh}"
JAR_PATH="${JAR_PATH:-$ROOT_DIR/target/orionkv-0.0.1-SNAPSHOT.jar}"

BASE_HTTP_PORT="${BASE_HTTP_PORT:-8080}"
BASE_GRPC_PORT="${BASE_GRPC_PORT:-9090}"
INITIAL_NODE_COUNT="${INITIAL_NODE_COUNT:-6}"
ADDED_NODE_ID="${ADDED_NODE_ID:-7}"
KILL_NODE_ID="${KILL_NODE_ID:-5}"

KEY="${KEY:-e2e-key}"
VALUE="${VALUE:-alpha}"
PUT_TIMESTAMP="${PUT_TIMESTAMP:-1000}"
DELETE_TIMESTAMP="${DELETE_TIMESTAMP:-2000}"

STARTUP_WAIT_SECS="${STARTUP_WAIT_SECS:-10}"
POST_ADD_WAIT_SECS="${POST_ADD_WAIT_SECS:-8}"
POST_KILL_WAIT_SECS="${POST_KILL_WAIT_SECS:-14}"
PORT_WAIT_ATTEMPTS="${PORT_WAIT_ATTEMPTS:-60}"
PORT_WAIT_SLEEP_SECS="${PORT_WAIT_SLEEP_SECS:-0.5}"

GOSSIP_INTERVAL_MS="${GOSSIP_INTERVAL_MS:-1000}"
SELF_HEARTBEAT_INTERVAL_MS="${SELF_HEARTBEAT_INTERVAL_MS:-500}"
FAILURE_DETECTION_INTERVAL_MS="${FAILURE_DETECTION_INTERVAL_MS:-1000}"
SUSPECT_TIMEOUT_MS="${SUSPECT_TIMEOUT_MS:-5000}"
DEAD_TIMEOUT_MS="${DEAD_TIMEOUT_MS:-12000}"

TMP_DIR="$(mktemp -d /tmp/orionkv-e2e.XXXXXX)"

cleanup() {
  "$KILL_SCRIPT" >/dev/null 2>&1 || true
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

wait_for_http_port() {
  local port="$1"
  local attempt
  for ((attempt=1; attempt<=PORT_WAIT_ATTEMPTS; attempt++)); do
    if curl -sS "http://127.0.0.1:${port}/api/kv/__readiness_probe__" >/dev/null 2>&1; then
      return 0
    fi
    sleep "$PORT_WAIT_SLEEP_SECS"
  done
  return 1
}

http_code() {
  local method="$1"
  local url="$2"
  local body_file="$3"
  shift 3
  curl -sS -o "$body_file" -w "%{http_code}" -X "$method" "$url" "$@"
}

membership_json() {
  local grpc_port="$1"
  grpcurl -plaintext -d '{}' -proto src/main/proto/controlplane.proto \
    "127.0.0.1:${grpc_port}" orionkv.node.ClusterRpc/GetMembership
}

assert_membership_count() {
  local grpc_port="$1"
  local expected_count="$2"
  local membership
  membership="$(membership_json "$grpc_port")"
  echo "$membership"

  local actual_count
  actual_count="$(printf '%s\n' "$membership" | jq '.membership | length')"
  if [[ "$actual_count" != "$expected_count" ]]; then
    echo "Expected membership count ${expected_count}, got ${actual_count}" >&2
    exit 1
  fi
}

assert_member_status() {
  local grpc_port="$1"
  local node_id="$2"
  local expected_status="$3"
  local membership
  membership="$(membership_json "$grpc_port")"
  echo "$membership"

  local actual_status
  actual_status="$(printf '%s\n' "$membership" | jq -r --arg node_id "$node_id" '.membership[] | select(.nodeId == $node_id) | .status')"
  if [[ "$actual_status" != "$expected_status" ]]; then
    echo "Expected ${node_id} to be ${expected_status}, got ${actual_status:-<missing>}" >&2
    exit 1
  fi
}

echo "==> Verifying prerequisites"
require_cmd java
require_cmd curl
require_cmd grpcurl
require_cmd jq

if [[ ! -f "$JAR_PATH" ]]; then
  echo "Jar not found at '$JAR_PATH'. Build first with: mvn -DskipTests package" >&2
  exit 1
fi

echo "==> Cleaning up any previous local cluster"
"$KILL_SCRIPT" >/dev/null 2>&1 || true

echo "==> Starting ${INITIAL_NODE_COUNT}-node cluster"
NODE_IDS="$(seq 1 "$INITIAL_NODE_COUNT" | tr '\n' ' ')" \
GOSSIP_INTERVAL_MS="$GOSSIP_INTERVAL_MS" \
SELF_HEARTBEAT_INTERVAL_MS="$SELF_HEARTBEAT_INTERVAL_MS" \
FAILURE_DETECTION_INTERVAL_MS="$FAILURE_DETECTION_INTERVAL_MS" \
SUSPECT_TIMEOUT_MS="$SUSPECT_TIMEOUT_MS" \
DEAD_TIMEOUT_MS="$DEAD_TIMEOUT_MS" \
"$START_SCRIPT"

echo "==> Waiting ${STARTUP_WAIT_SECS}s for cluster convergence"
sleep "$STARTUP_WAIT_SECS"

echo "==> Waiting for all HTTP ports to respond"
for node_id in $(seq 1 "$INITIAL_NODE_COUNT"); do
  port=$((BASE_HTTP_PORT + node_id))
  if ! wait_for_http_port "$port"; then
    echo "HTTP port ${port} did not become ready in time" >&2
    exit 1
  fi
done

echo "==> Membership after startup"
assert_membership_count $((BASE_GRPC_PORT + 1)) "$INITIAL_NODE_COUNT"

echo "==> Writing key through node-1"
put_body="${TMP_DIR}/put.json"
put_status="$(http_code PUT "http://127.0.0.1:$((BASE_HTTP_PORT + 1))/api/kv/${KEY}" "$put_body" \
  -H "Content-Type: application/json" \
  -d "{\"value\":\"${VALUE}\",\"timestamp\":${PUT_TIMESTAMP}}")"
cat "$put_body"
echo
if [[ "$put_status" != "200" ]]; then
  echo "PUT failed with HTTP ${put_status}" >&2
  exit 1
fi

echo "==> Reading key through node-1"
get1_body="${TMP_DIR}/get-node1.json"
get1_status="$(http_code GET "http://127.0.0.1:$((BASE_HTTP_PORT + 1))/api/kv/${KEY}" "$get1_body")"
cat "$get1_body"
echo
if [[ "$get1_status" != "200" ]]; then
  echo "GET on node-1 failed with HTTP ${get1_status}" >&2
  exit 1
fi

echo "==> Reading key through node-3"
get3_body="${TMP_DIR}/get-node3.json"
get3_status="$(http_code GET "http://127.0.0.1:$((BASE_HTTP_PORT + 3))/api/kv/${KEY}" "$get3_body")"
cat "$get3_body"
echo
if [[ "$get3_status" != "200" ]]; then
  echo "GET on node-3 failed with HTTP ${get3_status}" >&2
  exit 1
fi

echo "==> Deleting key through node-1"
delete_body="${TMP_DIR}/delete.json"
delete_status="$(http_code DELETE "http://127.0.0.1:$((BASE_HTTP_PORT + 1))/api/kv/${KEY}?timestamp=${DELETE_TIMESTAMP}" "$delete_body")"
cat "$delete_body"
echo
if [[ "$delete_status" != "200" ]]; then
  echo "DELETE failed with HTTP ${delete_status}" >&2
  exit 1
fi

echo "==> Confirming tombstone behavior through node-1"
after_delete_body="${TMP_DIR}/after-delete.json"
after_delete_status="$(http_code GET "http://127.0.0.1:$((BASE_HTTP_PORT + 1))/api/kv/${KEY}" "$after_delete_body")"
cat "$after_delete_body"
echo
if [[ "$after_delete_status" != "404" ]]; then
  echo "Expected GET after delete to return 404, got ${after_delete_status}" >&2
  exit 1
fi

echo "==> Adding node-${ADDED_NODE_ID}"
nohup java -jar "$JAR_PATH" \
  --server.port="$((BASE_HTTP_PORT + ADDED_NODE_ID))" \
  --node.node-id="node-${ADDED_NODE_ID}" \
  --node.address="127.0.0.1:$((BASE_GRPC_PORT + ADDED_NODE_ID))" \
  --node.seed-address="127.0.0.1:$((BASE_GRPC_PORT + 1))" \
  --node.gossip-interval-ms="${GOSSIP_INTERVAL_MS}" \
  --node.self-heartbeat-interval-ms="${SELF_HEARTBEAT_INTERVAL_MS}" \
  --node.failure-detection-interval-ms="${FAILURE_DETECTION_INTERVAL_MS}" \
  --node.suspect-timeout-ms="${SUSPECT_TIMEOUT_MS}" \
  --node.dead-timeout-ms="${DEAD_TIMEOUT_MS}" \
  >"logs/node-${ADDED_NODE_ID}.log" 2>&1 &
echo $! >"pids/node-${ADDED_NODE_ID}.pid"

if ! wait_for_http_port "$((BASE_HTTP_PORT + ADDED_NODE_ID))"; then
  echo "node-${ADDED_NODE_ID} HTTP port did not become ready in time" >&2
  exit 1
fi

echo "==> Waiting ${POST_ADD_WAIT_SECS}s for membership convergence after adding node-${ADDED_NODE_ID}"
sleep "$POST_ADD_WAIT_SECS"

echo "==> Membership after adding node-${ADDED_NODE_ID}"
assert_membership_count $((BASE_GRPC_PORT + 1)) "$((INITIAL_NODE_COUNT + 1))"

echo "==> Killing node-${KILL_NODE_ID}"
kill -9 "$(cat "pids/node-${KILL_NODE_ID}.pid")"

echo "==> Waiting ${POST_KILL_WAIT_SECS}s for failure detection convergence"
sleep "$POST_KILL_WAIT_SECS"

echo "==> Membership after killing node-${KILL_NODE_ID}"
assert_member_status $((BASE_GRPC_PORT + 1)) "node-${KILL_NODE_ID}" "DEAD"

echo "==> Smoke test passed"
