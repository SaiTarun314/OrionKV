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
REQUEST_PREFIX="${REQUEST_PREFIX:-fair-load}"
CLIENT_ROUTER_BASE_URL="${CLIENT_ROUTER_BASE_URL:-http://127.0.0.1:8090}"
TARGETS="${TARGETS:-}"

if ! command -v grpcurl >/dev/null 2>&1; then
  echo "grpcurl is required"
  exit 1
fi

if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 is required"
  exit 1
fi

if ! command -v curl >/dev/null 2>&1; then
  echo "curl is required"
  exit 1
fi

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

discover_targets() {
  local json
  if ! json="$(curl -fsS "${CLIENT_ROUTER_BASE_URL}/client/nodes")"; then
    echo "Failed to fetch ${CLIENT_ROUTER_BASE_URL}/client/nodes" >&2
    return 1
  fi

  python3 -c '
import json
import sys

try:
    data = json.load(sys.stdin)
except json.JSONDecodeError as exc:
    print(f"Invalid JSON from /client/nodes: {exc}", file=sys.stderr)
    sys.exit(1)

nodes = data.get("nodes", [])
alive = []
for node in nodes:
    if node.get("status") != "ALIVE":
        continue
    address = (node.get("grpcAddress") or "").replace("http://", "").replace("https://", "").strip()
    if address:
        alive.append(address)

for address in sorted(set(alive)):
    print(address)
' <<<"$json"
}

if [[ -z "$TARGETS" ]]; then
  TARGETS="$(discover_targets | paste -sd' ' -)"
fi

read -r -a TARGET_ARRAY <<<"$TARGETS"
if (( ${#TARGET_ARRAY[@]} == 0 )); then
  echo "No coordinator targets available. Set TARGETS or ensure /client/nodes has ALIVE entries."
  exit 1
fi

echo "Starting fair cluster load"
echo "  total_keys=${TOTAL_KEYS}"
echo "  start_index=${START_INDEX}"
echo "  concurrency=${CONCURRENCY}"
echo "  target_count=${#TARGET_ARRAY[@]}"
echo "  targets=${TARGETS}"

start_epoch="$(date +%s)"

seq 1 "$TOTAL_KEYS" | xargs -P "$CONCURRENCY" -n 1 bash -lc '
  ROOT_DIR="$1"
  TARGETS="$2"
  START_INDEX="$3"
  KEY_PREFIX="$4"
  VALUE_PREFIX="$5"
  TIMESTAMP_BASE="$6"
  PROTO_FILE="$7"
  REQUEST_PREFIX="$8"
  i="$9"

  cd "$ROOT_DIR"
  read -r -a TARGET_ARRAY <<<"$TARGETS"
  target_count="${#TARGET_ARRAY[@]}"
  target_index=$(( (i - 1) % target_count ))
  target="${TARGET_ARRAY[$target_index]}"
  key_index=$(( START_INDEX + i - 1 ))
  key="${KEY_PREFIX}-${key_index}"
  value="${VALUE_PREFIX}-${key_index}"
  timestamp=$(( TIMESTAMP_BASE + key_index ))

  grpcurl -plaintext \
    -d "{\"requestId\":\"${REQUEST_PREFIX}-${key_index}\",\"key\":\"${key}\",\"value\":\"${value}\",\"timestamp\":${timestamp}}" \
    -proto "$PROTO_FILE" \
    "$target" \
    orionkv.node.CoordinationRpc/Put >/dev/null
' _ "$ROOT_DIR" "$TARGETS" "$START_INDEX" "$KEY_PREFIX" "$VALUE_PREFIX" "$TIMESTAMP_BASE" "$PROTO_FILE" "$REQUEST_PREFIX"

end_epoch="$(date +%s)"
duration=$(( end_epoch - start_epoch ))

echo "Fair cluster load complete"
echo "  inserted=${TOTAL_KEYS}"
echo "  duration_seconds=${duration}"
