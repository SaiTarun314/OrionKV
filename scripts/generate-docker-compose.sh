#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$ROOT_DIR"

NODE_COUNT="${NODE_COUNT:-100}"
NODE_ID_OFFSET="${NODE_ID_OFFSET:-0}"
HTTP_PORT_BASE="${HTTP_PORT_BASE:-18080}"
GRPC_PORT_BASE="${GRPC_PORT_BASE:-19090}"
SERVER_PORT="${SERVER_PORT:-8080}"
INTERNAL_GRPC_PORT="${INTERNAL_GRPC_PORT:-9090}"
NETWORK_MODE="${NETWORK_MODE:-host}"
HOST_IP="${HOST_IP:-127.0.0.1}"
SEED_HOST_IP="${SEED_HOST_IP:-$HOST_IP}"
IMAGE_NAME="${IMAGE_NAME:-orionkv:local}"
OUTPUT_FILE="${OUTPUT_FILE:-docker-compose.generated.yml}"

GOSSIP_INTERVAL_MS="${GOSSIP_INTERVAL_MS:-1000}"
SELF_HEARTBEAT_INTERVAL_MS="${SELF_HEARTBEAT_INTERVAL_MS:-500}"
FAILURE_DETECTION_INTERVAL_MS="${FAILURE_DETECTION_INTERVAL_MS:-1000}"
SUSPECT_TIMEOUT_MS="${SUSPECT_TIMEOUT_MS:-15000}"
DEAD_TIMEOUT_MS="${DEAD_TIMEOUT_MS:-45000}"
VIRTUAL_NODE_COUNT="${VIRTUAL_NODE_COUNT:-512}"
REPLICATION_FACTOR="${REPLICATION_FACTOR:-3}"
WRITE_QUORUM="${WRITE_QUORUM:-2}"
READ_QUORUM="${READ_QUORUM:-2}"
JAVA_OPTS="${JAVA_OPTS:--Xms32m -Xmx128m}"
CLIENT_ROUTER_URL="${CLIENT_ROUTER_URL:-http://152.7.177.154:8090/client/nodes/seed}"

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

if ! [[ "$NODE_COUNT" =~ ^[0-9]+$ ]] || (( NODE_COUNT < 1 )); then
  echo "NODE_COUNT must be a positive integer"
  exit 1
fi

if ! [[ "$NODE_ID_OFFSET" =~ ^[0-9]+$ ]]; then
  echo "NODE_ID_OFFSET must be a non-negative integer"
  exit 1
fi

cat > "$OUTPUT_FILE" <<EOF
services:
EOF

for i in $(seq 1 "$NODE_COUNT"); do
  node_id=$((NODE_ID_OFFSET + i))
  http_port=$((HTTP_PORT_BASE + node_id))
  grpc_port=$((GRPC_PORT_BASE + node_id))
  server_port="$SERVER_PORT"
  bind_port="$INTERNAL_GRPC_PORT"
  seed_args=""
  if (( node_id > 1 )); then
    seed_args=" --node.seed-address=${SEED_HOST_IP}:$((GRPC_PORT_BASE + 1))"
  fi

  if [[ "$NETWORK_MODE" == "host" ]]; then
    server_port="$http_port"
    bind_port="$grpc_port"
  fi

  cat >> "$OUTPUT_FILE" <<EOF
  node-$node_id:
    image: ${IMAGE_NAME}
    container_name: orionkv-node-$node_id
    hostname: node-$node_id
    restart: unless-stopped
    mem_limit: 256m
    cpus: 0.75
EOF

  if [[ "$NETWORK_MODE" == "host" ]]; then
    cat >> "$OUTPUT_FILE" <<EOF
    network_mode: host
EOF
  fi

  cat >> "$OUTPUT_FILE" <<EOF
    environment:
      JAVA_OPTS: "${JAVA_OPTS}"
      APP_ARGS: >-
        --server.port=${server_port}
        --node.node-id=node-$node_id
        --node.address=${HOST_IP}:${grpc_port}
        --node.bind-port=${bind_port}
        --node.client-router-base-url=${CLIENT_ROUTER_BASE_URL}
        ${seed_args}
        --node.gossip-interval-ms=${GOSSIP_INTERVAL_MS}
        --node.self-heartbeat-interval-ms=${SELF_HEARTBEAT_INTERVAL_MS}
        --node.failure-detection-interval-ms=${FAILURE_DETECTION_INTERVAL_MS}
        --node.suspect-timeout-ms=${SUSPECT_TIMEOUT_MS}
        --node.dead-timeout-ms=${DEAD_TIMEOUT_MS}
        --node.virtual-node-count=${VIRTUAL_NODE_COUNT}
        --node.replication-factor=${REPLICATION_FACTOR}
        --node.write-quorum=${WRITE_QUORUM}
        --node.read-quorum=${READ_QUORUM}
        --dataplane.storage.log-path=/app/data/node-${node_id}.wal.log
    volumes:
      - ./docker-data/node-$node_id:/app/data
EOF

  if [[ "$NETWORK_MODE" != "host" ]]; then
    cat >> "$OUTPUT_FILE" <<EOF
    ports:
      - "${http_port}:${SERVER_PORT}"
      - "${grpc_port}:${INTERNAL_GRPC_PORT}"
EOF
  fi
done

cat >> "$OUTPUT_FILE" <<EOF

name: orionkv
EOF

cat <<MSG
Generated ${OUTPUT_FILE}

Image: ${IMAGE_NAME}
Nodes: ${NODE_COUNT}
Node ID offset: ${NODE_ID_OFFSET}
Network mode: ${NETWORK_MODE}
Host IP: ${HOST_IP}
Seed host IP: ${SEED_HOST_IP}
Client router: ${CLIENT_ROUTER_BASE_URL}
Host port ranges:
  HTTP: ${HTTP_PORT_BASE}+node_id
  gRPC: ${GRPC_PORT_BASE}+node_id

Next:
  docker build -t ${IMAGE_NAME} .
  docker compose -f ${OUTPUT_FILE} up -d
MSG
