#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$ROOT_DIR"

NODE_COUNT="${NODE_COUNT:-100}"
HTTP_PORT_BASE="${HTTP_PORT_BASE:-18080}"
GRPC_PORT_BASE="${GRPC_PORT_BASE:-19090}"
SERVER_PORT="${SERVER_PORT:-8080}"
INTERNAL_GRPC_PORT="${INTERNAL_GRPC_PORT:-9090}"
HOST_IP="${HOST_IP:-127.0.0.1}"
IMAGE_NAME="${IMAGE_NAME:-orionkv:local}"
OUTPUT_FILE="${OUTPUT_FILE:-docker-compose.generated.yml}"

GOSSIP_INTERVAL_MS="${GOSSIP_INTERVAL_MS:-1000}"
SELF_HEARTBEAT_INTERVAL_MS="${SELF_HEARTBEAT_INTERVAL_MS:-500}"
FAILURE_DETECTION_INTERVAL_MS="${FAILURE_DETECTION_INTERVAL_MS:-1000}"
SUSPECT_TIMEOUT_MS="${SUSPECT_TIMEOUT_MS:-15000}"
DEAD_TIMEOUT_MS="${DEAD_TIMEOUT_MS:-45000}"
VIRTUAL_NODE_COUNT="${VIRTUAL_NODE_COUNT:-32}"
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

cat > "$OUTPUT_FILE" <<EOF
services:
EOF

for i in $(seq 1 "$NODE_COUNT"); do
  http_port=$((HTTP_PORT_BASE + i))
  grpc_port=$((GRPC_PORT_BASE + i))
  seed_args=""
  if (( i > 1 )); then
    seed_args=" --node.seed-address=${HOST_IP}:$((GRPC_PORT_BASE + 1))"
  fi

  cat >> "$OUTPUT_FILE" <<EOF
  node-$i:
    image: ${IMAGE_NAME}
    container_name: orionkv-node-$i
    hostname: node-$i
    restart: unless-stopped
    mem_limit: 256m
    cpus: 0.75
    environment:
      JAVA_OPTS: "${JAVA_OPTS}"
      APP_ARGS: >-
        --server.port=${SERVER_PORT}
        --node.node-id=node-$i
        --node.address=${HOST_IP}:${grpc_port}
        --node.bind-port=${INTERNAL_GRPC_PORT}
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
        --dataplane.storage.log-path=/app/data/node-${i}.wal.log
    volumes:
      - ./docker-data/node-$i:/app/data
    ports:
      - "${http_port}:${SERVER_PORT}"
      - "${grpc_port}:${INTERNAL_GRPC_PORT}"
EOF
done

cat >> "$OUTPUT_FILE" <<EOF

name: orionkv
EOF

cat <<MSG
Generated ${OUTPUT_FILE}

Image: ${IMAGE_NAME}
Nodes: ${NODE_COUNT}
Host IP: ${HOST_IP}
Client router: ${CLIENT_ROUTER_BASE_URL}
Host port ranges:
  HTTP: ${HTTP_PORT_BASE}+node_id
  gRPC: ${GRPC_PORT_BASE}+node_id

Next:
  docker build -t ${IMAGE_NAME} .
  docker compose -f ${OUTPUT_FILE} up -d
MSG
