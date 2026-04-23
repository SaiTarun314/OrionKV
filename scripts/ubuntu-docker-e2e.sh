#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$ROOT_DIR"

NODE_COUNT="${NODE_COUNT:-25}"
NODE_ID_OFFSET="${NODE_ID_OFFSET:-0}"
TOTAL_KEYS="${TOTAL_KEYS:-10000}"
KEY_START_INDEX="${KEY_START_INDEX:-$(( (NODE_ID_OFFSET / NODE_COUNT) * TOTAL_KEYS + 1 ))}"
CONCURRENCY="${CONCURRENCY:-64}"
VIRTUAL_NODE_COUNT="${VIRTUAL_NODE_COUNT:-1024}"
REPLICATION_FACTOR="${REPLICATION_FACTOR:-3}"
WRITE_QUORUM="${WRITE_QUORUM:-2}"
READ_QUORUM="${READ_QUORUM:-2}"
JAVA_OPTS="${JAVA_OPTS:--Xms16m -Xmx96m}"
IMAGE_NAME="${IMAGE_NAME:-orionkv:local}"
NETWORK_MODE="${NETWORK_MODE:-host}"
COORDINATOR_PORTS="${COORDINATOR_PORTS:-}"
SMOKE_NODE_COUNT="${SMOKE_NODE_COUNT:-$NODE_COUNT}"
LOAD_KEY_PREFIX="${LOAD_KEY_PREFIX:-bulk-key}"
LOAD_VALUE_PREFIX="${LOAD_VALUE_PREFIX:-bulk-value}"
SAMPLE_SIZE="${SAMPLE_SIZE:-500}"
WAIT_SECONDS="${WAIT_SECONDS:-20}"
SEED_WAIT_SECONDS="${SEED_WAIT_SECONDS:-30}"
CLIENT_ROUTER_URL="${CLIENT_ROUTER_URL:-http://152.7.177.154:8090/client/nodes/seed}"
HOST_IP="${HOST_IP:-127.0.0.1}"
SEED_HOST_IP="${SEED_HOST_IP:-}"

compose_cmd() {
  if docker compose version >/dev/null 2>&1; then
    docker compose "$@"
    return
  fi
  if command -v docker-compose >/dev/null 2>&1; then
    docker-compose "$@"
    return
  fi
  echo "Neither 'docker compose' nor 'docker-compose' is available" >&2
  exit 1
}

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1"
    exit 1
  fi
}

require_cmd docker
require_cmd grpcurl
require_cmd python3
require_cmd curl

normalize_client_router_base_url() {
  local url="$1"
  url="${url%/}"
  if [[ "$url" == */client/nodes/seed ]]; then
    echo "${url%/client/nodes/seed}"
    return
  fi
  echo "$url"
}

CLIENT_ROUTER_BASE_URL="$(normalize_client_router_base_url "$CLIENT_ROUTER_URL")"
SEED_NODE_ID="${SEED_NODE_ID:-1}"
SEED_GRPC_PORT=$((19090 + SEED_NODE_ID))

wait_for_seed() {
  local host="${1:-127.0.0.1}"
  local grpc_port="${2:-19091}"
  local timeout_seconds="${3:-30}"
  local deadline=$(( $(date +%s) + timeout_seconds ))

  while (( $(date +%s) < deadline )); do
    if grpcurl -plaintext -d '{}' -proto src/main/proto/controlplane.proto \
      "${host}:${grpc_port}" orionkv.node.ClusterRpc/GetMembership >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done

  return 1
}

membership_snapshot() {
  local host="${1:-127.0.0.1}"
  local grpc_port="${2:-19091}"
  grpcurl -plaintext -d '{}' -proto src/main/proto/controlplane.proto \
    "${host}:${grpc_port}" orionkv.node.ClusterRpc/GetMembership
}

wait_for_node_in_membership() {
  local node_id="$1"
  local host="${2:-127.0.0.1}"
  local grpc_port="${3:-19091}"
  local timeout_seconds="${4:-60}"
  local deadline=$(( $(date +%s) + timeout_seconds ))

  while (( $(date +%s) < deadline )); do
    local snapshot
    if snapshot="$(membership_snapshot "$host" "$grpc_port" 2>/dev/null)"; then
      if python3 -c '
import json
import sys

node_id = sys.argv[1]
data = json.load(sys.stdin)
for member in data.get("membership", []):
    if member.get("nodeId") == node_id and member.get("status") == "ALIVE":
        raise SystemExit(0)
raise SystemExit(1)
' "$node_id" <<<"$snapshot"
      then
        return 0
      fi
    fi
    sleep 1
  done

  return 1
}

client_router_nodes() {
  curl -fsS "${CLIENT_ROUTER_BASE_URL}/client/nodes"
}

detect_seed_host_from_client_router() {
  local seed_node_id="$1"
  python3 - "$seed_node_id" <<'PY' < <(client_router_nodes)
import json
import sys

seed_node_id = f"node-{sys.argv[1]}"
data = json.load(sys.stdin)
for node in data.get("nodes", []):
    if node.get("nodeId") == seed_node_id and node.get("status") == "ALIVE":
        address = (node.get("grpcAddress") or "").replace("http://", "").replace("https://", "")
        if ":" in address:
            print(address.rsplit(":", 1)[0])
            raise SystemExit(0)
raise SystemExit(1)
PY
}

register_node_with_client_router() {
  local node_id="$1"
  local grpc_address="$2"
  curl -fsS -X POST "${CLIENT_ROUTER_BASE_URL}/client/nodes/register" \
    -H "Content-Type: application/json" \
    -d "{\"nodeId\":\"${node_id}\",\"grpcAddress\":\"${grpc_address}\"}" >/dev/null
}

client_router_alive_count() {
  python3 -c '
import json
import sys

data = json.load(sys.stdin)
nodes = data.get("nodes", [])
alive = sum(1 for node in nodes if node.get("status") == "ALIVE")
print(alive)
' < <(client_router_nodes)
}

confirm_node_with_client_router() {
  local node_id="$1"
  local seed_grpc_address="${2:-$SEED_GRPC_ADDRESS}"
  local max_attempts="${3:-5}"
  local sleep_seconds="${4:-1}"

  local attempt
  for attempt in $(seq 1 "$max_attempts"); do
    echo "==> Confirming ${node_id} with client-router (attempt ${attempt}/${max_attempts})"
    local response
    if response="$(curl -fsS -X POST "${CLIENT_ROUTER_BASE_URL}/client/nodes/confirm" \
      -H "Content-Type: application/json" \
      -d "{\"joiningNodeId\":\"${node_id}\",\"seedGrpcAddress\":\"${seed_grpc_address}\",\"pollAttempts\":10,\"pollDelayMs\":1000}")"
    then
      local confirmed
      confirmed="$(python3 -c '
import json
import sys

data = json.load(sys.stdin)
print("true" if data.get("confirmed") else "false")
      ' <<<"$response")"
      if [[ "$confirmed" == "true" ]]; then
        local alive_count
        alive_count="$(python3 -c '
import json
import sys

data = json.load(sys.stdin)
registry = data.get("registry") or {}
nodes = registry.get("nodes") or []
alive = sum(1 for node in nodes if node.get("status") == "ALIVE")
print(alive)
        ' <<<"$response")"
        echo "==> Client-router now sees ${alive_count} alive node(s)"
        return 0
      fi
    fi
    sleep "$sleep_seconds"
  done

  return 1
}

if [[ -z "${SEED_HOST_IP}" ]] && (( NODE_ID_OFFSET > 0 )); then
  SEED_HOST_IP="$(detect_seed_host_from_client_router "$SEED_NODE_ID" 2>/dev/null || true)"
fi
SEED_MEMBERSHIP_HOST="${SEED_HOST_IP:-$HOST_IP}"
SEED_GRPC_ADDRESS="${SEED_MEMBERSHIP_HOST}:${SEED_GRPC_PORT}"

if (( NODE_ID_OFFSET > 0 )) && [[ "$SEED_MEMBERSHIP_HOST" == "$HOST_IP" ]]; then
  echo "WARNING: NODE_ID_OFFSET=${NODE_ID_OFFSET} but seed host resolved to local HOST_IP=${HOST_IP}."
  echo "         Set SEED_HOST_IP explicitly if seed node-${SEED_NODE_ID} is on another machine."
fi

echo "==> Building image ${IMAGE_NAME}"
docker build -t "${IMAGE_NAME}" .

echo "==> Resetting previous docker cluster state"
compose_cmd -f docker-compose.generated.yml down --remove-orphans >/dev/null 2>&1 || true
rm -rf docker-data

echo "==> Generating compose for ${NODE_COUNT} nodes"
NODE_COUNT="${NODE_COUNT}" \
NODE_ID_OFFSET="${NODE_ID_OFFSET}" \
VIRTUAL_NODE_COUNT="${VIRTUAL_NODE_COUNT}" \
REPLICATION_FACTOR="${REPLICATION_FACTOR}" \
WRITE_QUORUM="${WRITE_QUORUM}" \
READ_QUORUM="${READ_QUORUM}" \
JAVA_OPTS="${JAVA_OPTS}" \
IMAGE_NAME="${IMAGE_NAME}" \
NETWORK_MODE="${NETWORK_MODE}" \
CLIENT_ROUTER_URL="${CLIENT_ROUTER_URL}" \
HOST_IP="${HOST_IP}" \
SEED_HOST_IP="${SEED_MEMBERSHIP_HOST}" \
./scripts/generate-docker-compose.sh

FIRST_NODE_ID=$((NODE_ID_OFFSET + 1))
LAST_NODE_ID=$((NODE_ID_OFFSET + NODE_COUNT))
FIRST_NODE_NAME="node-${FIRST_NODE_ID}"
FIRST_GRPC_PORT=$((19090 + FIRST_NODE_ID))
FIRST_GRPC_ADDRESS="${HOST_IP}:${FIRST_GRPC_PORT}"

if [[ -z "$COORDINATOR_PORTS" ]]; then
  coordinator_ports=()
  for position in $(seq 1 "$NODE_COUNT"); do
    coordinator_ports+=("$((19090 + NODE_ID_OFFSET + position))")
  done
  COORDINATOR_PORTS="${coordinator_ports[*]}"
fi
echo "==> Using coordinator ports: ${COORDINATOR_PORTS}"
echo "==> Using key start index: ${KEY_START_INDEX}"

echo "==> Starting first node ${FIRST_NODE_NAME}"
compose_cmd -f docker-compose.generated.yml up -d "${FIRST_NODE_NAME}"

echo "==> Waiting up to ${SEED_WAIT_SECONDS}s for ${FIRST_NODE_NAME} to become reachable"
if ! wait_for_seed "127.0.0.1" "${FIRST_GRPC_PORT}" "${SEED_WAIT_SECONDS}"; then
  echo "${FIRST_NODE_NAME} did not become reachable in time"
  compose_cmd -f docker-compose.generated.yml logs --tail=200 "${FIRST_NODE_NAME}" || true
  exit 1
fi

if ! wait_for_node_in_membership "${FIRST_NODE_NAME}" "127.0.0.1" "${FIRST_GRPC_PORT}" "${SEED_WAIT_SECONDS}"; then
  echo "${FIRST_NODE_NAME} did not appear as ALIVE in membership in time"
  membership_snapshot "127.0.0.1" "${FIRST_GRPC_PORT}" || true
  exit 1
fi

echo "==> Registering ${FIRST_NODE_NAME} with client-router"
register_node_with_client_router "${FIRST_NODE_NAME}" "${FIRST_GRPC_ADDRESS}"
echo "==> Client-router now sees $(client_router_alive_count) alive node(s)"
if ! confirm_node_with_client_router "${FIRST_NODE_NAME}" "${SEED_GRPC_ADDRESS}"; then
  echo "client-router confirmation failed for ${FIRST_NODE_NAME}"
  client_router_nodes || true
  exit 1
fi

if (( NODE_COUNT > 1 )); then
  echo "==> Starting remaining docker nodes"
  compose_cmd -f docker-compose.generated.yml up -d
fi

for node_id in $(seq "$((FIRST_NODE_ID + 1))" "$LAST_NODE_ID"); do
  node_name="node-${node_id}"
  grpc_port=$((19090 + node_id))
  grpc_address="${HOST_IP}:${grpc_port}"

  echo "==> Waiting for ${node_name} gRPC on 127.0.0.1:${grpc_port}"
  if ! wait_for_seed "127.0.0.1" "${grpc_port}" "${SEED_WAIT_SECONDS}"; then
    echo "${node_name} did not become reachable in time"
    compose_cmd -f docker-compose.generated.yml logs --tail=200 "${node_name}" || true
    exit 1
  fi

  echo "==> Waiting for ${node_name} to appear in seed membership via ${SEED_MEMBERSHIP_HOST}:${SEED_GRPC_PORT}"
  if ! wait_for_node_in_membership "${node_name}" "${SEED_MEMBERSHIP_HOST}" "${SEED_GRPC_PORT}" "${SEED_WAIT_SECONDS}"; then
    echo "${node_name} did not appear as ALIVE in cluster membership in time"
    compose_cmd -f docker-compose.generated.yml logs --tail=200 "${node_name}" || true
    membership_snapshot "${SEED_MEMBERSHIP_HOST}" "${SEED_GRPC_PORT}" || true
    exit 1
  fi

  echo "==> Registering ${node_name} with client-router"
  register_node_with_client_router "${node_name}" "${grpc_address}"
  echo "==> Client-router now sees $(client_router_alive_count) alive node(s)"

  if ! confirm_node_with_client_router "${node_name}" "${SEED_GRPC_ADDRESS}"; then
    echo "client-router confirmation failed for ${node_name}"
    client_router_nodes || true
    exit 1
  fi
done

echo "==> Waiting ${WAIT_SECONDS}s for gossip/join convergence"
sleep "${WAIT_SECONDS}"

echo "==> Cluster smoke check"
NODE_COUNT="${SMOKE_NODE_COUNT}" \
NODE_ID_OFFSET="${NODE_ID_OFFSET}" \
SEED_GRPC_PORT="${FIRST_GRPC_PORT}" \
./scripts/docker-cluster-smoke.sh

echo "==> Bulk loading ${TOTAL_KEYS} keys with concurrency ${CONCURRENCY}"
TOTAL_KEYS="${TOTAL_KEYS}" \
START_INDEX="${KEY_START_INDEX}" \
CONCURRENCY="${CONCURRENCY}" \
PORTS="${COORDINATOR_PORTS}" \
KEY_PREFIX="${LOAD_KEY_PREFIX}" \
VALUE_PREFIX="${LOAD_VALUE_PREFIX}" \
./scripts/docker-bulk-load.sh

echo "==> Balance and replica audit"
TOTAL_KEYS="${TOTAL_KEYS}" \
START_INDEX="${KEY_START_INDEX}" \
SAMPLE_SIZE="${SAMPLE_SIZE}" \
KEY_PREFIX="${LOAD_KEY_PREFIX}" \
COORDINATOR_PORTS="${COORDINATOR_PORTS}" \
MEMBERSHIP_PORT="${FIRST_GRPC_PORT}" \
./scripts/docker-balance-audit.sh

echo "==> Final client-router node table"
client_router_nodes

echo "==> Final client-router alive node count: $(client_router_alive_count)"

cat <<EOF

E2E flow complete.
Docker network mode: ${NETWORK_MODE}
Node ID offset: ${NODE_ID_OFFSET}
Client router seed endpoint: ${CLIENT_ROUTER_URL}
Client router base URL: ${CLIENT_ROUTER_BASE_URL}
Host IP for advertised node addresses: ${HOST_IP}

Useful follow-ups:
  compose_cmd -f docker-compose.generated.yml ps
  sudo docker stop orionkv-node-${LAST_NODE_ID}
  NODE_COUNT=${NODE_COUNT} NODE_ID_OFFSET=${NODE_ID_OFFSET} ./scripts/docker-cluster-smoke.sh
  TOTAL_KEYS=${TOTAL_KEYS} START_INDEX=${KEY_START_INDEX} SAMPLE_SIZE=${SAMPLE_SIZE} ./scripts/docker-balance-audit.sh
EOF
