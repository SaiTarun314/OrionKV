#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$ROOT_DIR"

TOTAL_KEYS="${TOTAL_KEYS:-50000}"
CONCURRENCY="${CONCURRENCY:-64}"
KEY_PREFIX="${KEY_PREFIX:-bulk-key}"
VALUE_PREFIX="${VALUE_PREFIX:-bulk-value}"
TIMESTAMP_BASE="${TIMESTAMP_BASE:-1710000000000}"
PROTO_FILE="${PROTO_FILE:-src/main/proto/coordination.proto}"
PORTS="${PORTS:-19091}"

if ! [[ "$TOTAL_KEYS" =~ ^[0-9]+$ ]] || (( TOTAL_KEYS < 1 )); then
  echo "TOTAL_KEYS must be a positive integer"
  exit 1
fi

if ! [[ "$CONCURRENCY" =~ ^[0-9]+$ ]] || (( CONCURRENCY < 1 )); then
  echo "CONCURRENCY must be a positive integer"
  exit 1
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

echo "Starting bulk load"
echo "  total_keys=${TOTAL_KEYS}"
echo "  concurrency=${CONCURRENCY}"
echo "  ports=${PORTS}"

start_epoch="$(date +%s)"

seq 1 "$TOTAL_KEYS" | xargs -P "$CONCURRENCY" -n 1 bash -lc '
  ROOT_DIR="$1"
  PORTS="$2"
  KEY_PREFIX="$3"
  VALUE_PREFIX="$4"
  TIMESTAMP_BASE="$5"
  PROTO_FILE="$6"
  i="$7"

  cd "$ROOT_DIR"
  read -r -a PORT_ARRAY <<< "$PORTS"
  port_count="${#PORT_ARRAY[@]}"
  port_index=$(( (i - 1) % port_count ))
  port="${PORT_ARRAY[$port_index]}"
  key="${KEY_PREFIX}-${i}"
  value="${VALUE_PREFIX}-${i}"
  timestamp=$(( TIMESTAMP_BASE + i ))

  grpcurl -plaintext \
    -d "{\"requestId\":\"load-${i}\",\"key\":\"${key}\",\"value\":\"${value}\",\"timestamp\":${timestamp}}" \
    -proto "$PROTO_FILE" \
    "127.0.0.1:${port}" \
    orionkv.node.CoordinationRpc/Put >/dev/null
' _ "$ROOT_DIR" "$PORTS" "$KEY_PREFIX" "$VALUE_PREFIX" "$TIMESTAMP_BASE" "$PROTO_FILE"

end_epoch="$(date +%s)"
duration=$(( end_epoch - start_epoch ))

echo "Bulk load complete"
echo "  inserted=${TOTAL_KEYS}"
echo "  duration_seconds=${duration}"
