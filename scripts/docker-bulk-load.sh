#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$ROOT_DIR"

TOTAL_KEYS="${TOTAL_KEYS:-10000}"
CONCURRENCY="${CONCURRENCY:-64}"
START_INDEX="${START_INDEX:-1}"
KEY_PREFIX="${KEY_PREFIX:-bulk-key}"
VALUE_PREFIX="${VALUE_PREFIX:-bulk-value}"
TIMESTAMP_BASE="${TIMESTAMP_BASE:-1710000000000}"
PROTO_FILE="${PROTO_FILE:-src/main/proto/coordination.proto}"
NODE_COUNT="${NODE_COUNT:-25}"
NODE_ID_OFFSET="${NODE_ID_OFFSET:-0}"
GRPC_PORT_BASE="${GRPC_PORT_BASE:-19090}"
PORTS="${PORTS:-}"

if ! [[ "$TOTAL_KEYS" =~ ^[0-9]+$ ]] || (( TOTAL_KEYS < 1 )); then
  echo "TOTAL_KEYS must be a positive integer"
  exit 1
fi

if ! [[ "$CONCURRENCY" =~ ^[0-9]+$ ]] || (( CONCURRENCY < 1 )); then
  echo "CONCURRENCY must be a positive integer"
  exit 1
fi

if ! [[ "$START_INDEX" =~ ^[0-9]+$ ]] || (( START_INDEX < 1 )); then
  echo "START_INDEX must be a positive integer"
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

if [[ -z "$PORTS" ]]; then
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
  PORTS="${computed_ports[*]}"
fi

read -r -a PORT_ARRAY <<< "$PORTS"
if (( ${#PORT_ARRAY[@]} == 0 )); then
  echo "PORTS must contain at least one gRPC port"
  exit 1
fi

run_put() {
  local i="$1"
  local port_count="${#PORT_ARRAY[@]}"
  local port_index=$(( (i - 1) % port_count ))
  local port="${PORT_ARRAY[$port_index]}"
  local key="${KEY_PREFIX}-${i}"
  local value="${VALUE_PREFIX}-${i}"
  local timestamp=$(( TIMESTAMP_BASE + i ))

  grpcurl -plaintext \
    -d "{\"requestId\":\"load-${i}\",\"key\":\"${key}\",\"value\":\"${value}\",\"timestamp\":${timestamp}}" \
    -proto "$PROTO_FILE" \
    "127.0.0.1:${port}" \
    orionkv.node.CoordinationRpc/Put >/dev/null
}

export ROOT_DIR TOTAL_KEYS CONCURRENCY KEY_PREFIX VALUE_PREFIX TIMESTAMP_BASE PROTO_FILE
export PORTS
export START_INDEX

echo "Starting bulk load"
echo "  total_keys=${TOTAL_KEYS}"
echo "  start_index=${START_INDEX}"
echo "  concurrency=${CONCURRENCY}"
echo "  ports=${PORTS}"

start_epoch="$(date +%s)"

seq 1 "$TOTAL_KEYS" | xargs -P "$CONCURRENCY" -n 1 bash -lc '
  ROOT_DIR="$1"
  PORTS="$2"
  START_INDEX="$3"
  KEY_PREFIX="$4"
  VALUE_PREFIX="$5"
  TIMESTAMP_BASE="$6"
  PROTO_FILE="$7"
  i="$8"

  cd "$ROOT_DIR"
  read -r -a PORT_ARRAY <<< "$PORTS"
  port_count="${#PORT_ARRAY[@]}"
  port_index=$(( (i - 1) % port_count ))
  port="${PORT_ARRAY[$port_index]}"
  key_index=$(( START_INDEX + i - 1 ))
  key="${KEY_PREFIX}-${key_index}"
  value="${VALUE_PREFIX}-${key_index}"
  timestamp=$(( TIMESTAMP_BASE + key_index ))

  grpcurl -plaintext \
    -d "{\"requestId\":\"load-${key_index}\",\"key\":\"${key}\",\"value\":\"${value}\",\"timestamp\":${timestamp}}" \
    -proto "$PROTO_FILE" \
    "127.0.0.1:${port}" \
    orionkv.node.CoordinationRpc/Put >/dev/null
' _ "$ROOT_DIR" "$PORTS" "$START_INDEX" "$KEY_PREFIX" "$VALUE_PREFIX" "$TIMESTAMP_BASE" "$PROTO_FILE"

end_epoch="$(date +%s)"
duration=$(( end_epoch - start_epoch ))

echo "Bulk load complete"
echo "  inserted=${TOTAL_KEYS}"
echo "  duration_seconds=${duration}"
