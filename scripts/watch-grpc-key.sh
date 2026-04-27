#!/usr/bin/env bash
set -euo pipefail

SCRIPT_PATH="${BASH_SOURCE[0]:-$0}"
ROOT_DIR="$(cd "$(dirname "${SCRIPT_PATH}")/.." && pwd -P)"
cd "$ROOT_DIR"

MODE="${MODE:-coordination-get}"
HOST="${HOST:-127.0.0.1}"
PORT="${PORT:-}"
KEY="${KEY:-}"
INTERVAL_SECONDS="${INTERVAL_SECONDS:-1}"
COUNT="${COUNT:-0}"
PROTO_FILE="${PROTO_FILE:-src/main/proto/coordination.proto}"
REQUEST_ID_PREFIX="${REQUEST_ID_PREFIX:-watch}"
RAW_OUTPUT="${RAW_OUTPUT:-false}"

usage() {
  cat <<'EOF'
Usage:
  ./scripts/watch-grpc-key.sh --mode coordination-get --port 19095 --key bulk-key-1
  ./scripts/watch-grpc-key.sh --mode replica-get --port 19093 --key bulk-key-1

Options:
  --mode coordination-get|replica-get
  --host HOST                     Default: 127.0.0.1
  --port PORT                     Required
  --key KEY                       Required
  --interval SECONDS              Default: 1
  --count N                       0 means run forever
  --proto FILE                    Default: src/main/proto/coordination.proto
  --request-id-prefix PREFIX      Default: watch
  --raw                           Print raw JSON response after summary
  --help

Examples:
  ./scripts/watch-grpc-key.sh --mode coordination-get --port 19095 --key bulk-key-1
  ./scripts/watch-grpc-key.sh --mode replica-get --host 127.0.0.1 --port 19093 --key bulk-key-1 --interval 0.5
EOF
}

while (( $# > 0 )); do
  case "$1" in
    --mode)
      MODE="${2:-}"
      shift 2
      ;;
    --host)
      HOST="${2:-}"
      shift 2
      ;;
    --port)
      PORT="${2:-}"
      shift 2
      ;;
    --key)
      KEY="${2:-}"
      shift 2
      ;;
    --interval)
      INTERVAL_SECONDS="${2:-}"
      shift 2
      ;;
    --count)
      COUNT="${2:-}"
      shift 2
      ;;
    --proto)
      PROTO_FILE="${2:-}"
      shift 2
      ;;
    --request-id-prefix)
      REQUEST_ID_PREFIX="${2:-}"
      shift 2
      ;;
    --raw)
      RAW_OUTPUT="true"
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 1
      ;;
  esac
done

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

require_cmd grpcurl
require_cmd python3

if [[ -z "$PORT" ]]; then
  echo "--port is required" >&2
  exit 1
fi

if [[ -z "$KEY" ]]; then
  echo "--key is required" >&2
  exit 1
fi

if [[ "$MODE" != "coordination-get" && "$MODE" != "replica-get" ]]; then
  echo "--mode must be one of: coordination-get, replica-get" >&2
  exit 1
fi

if ! [[ "$COUNT" =~ ^[0-9]+$ ]]; then
  echo "--count must be a non-negative integer" >&2
  exit 1
fi

TMP_FILES=()
cleanup() {
  if (( ${#TMP_FILES[@]} > 0 )); then
    rm -f "${TMP_FILES[@]}" 2>/dev/null || true
  fi
}
trap cleanup EXIT

service_for_mode() {
  case "$1" in
    coordination-get) echo "orionkv.node.CoordinationRpc/Get" ;;
    replica-get) echo "orionkv.node.ReplicaDataRpc/GetReplica" ;;
  esac
}

request_json() {
  local request_id="$1"
  printf '{"requestId":"%s","key":"%s"}' "$request_id" "$KEY"
}

print_summary() {
  local mode="$1"
  local response_file="$2"
  python3 - "$mode" "$response_file" <<'PY'
import json
import pathlib
import sys

mode = sys.argv[1]
payload = pathlib.Path(sys.argv[2]).read_text()
data = json.loads(payload)

if mode == "coordination-get":
    summary = [
        f"found={str(data.get('found', False)).lower()}",
        f"value={json.dumps(data.get('value', ''))}",
        f"timestamp={data.get('timestamp', 0)}",
        f"responses={data.get('responseCount', 0)}/{data.get('requiredResponses', 0)}",
        f"replicas={','.join(data.get('replicaNodeIds', [])) or '-'}",
        f"responded={','.join(data.get('respondedNodeIds', [])) or '-'}",
        f"message={json.dumps(data.get('message', ''))}",
    ]
else:
    summary = [
        f"responded={str(data.get('responded', False)).lower()}",
        f"found={str(data.get('found', False)).lower()}",
        f"node={data.get('nodeId', '') or '-'}",
        f"value={json.dumps(data.get('value', ''))}",
        f"timestamp={data.get('timestamp', 0)}",
        f"tombstone={str(data.get('tombstone', False)).lower()}",
        f"message={json.dumps(data.get('message', ''))}",
    ]

print(" | ".join(summary))
PY
}

iteration=0
while :; do
  iteration=$((iteration + 1))
  request_id="${REQUEST_ID_PREFIX}-${iteration}"
  response_file="$(mktemp)"
  error_file="$(mktemp)"
  TMP_FILES+=("$response_file" "$error_file")

  timestamp="$(date '+%Y-%m-%d %H:%M:%S')"
  echo "[${timestamp}] iteration=${iteration} mode=${MODE} target=${HOST}:${PORT} key=${KEY}"

  if grpcurl -plaintext \
    -d "$(request_json "$request_id")" \
    -proto "$PROTO_FILE" \
    "${HOST}:${PORT}" \
    "$(service_for_mode "$MODE")" >"$response_file" 2>"$error_file"
  then
    print_summary "$MODE" "$response_file"
    if [[ "$RAW_OUTPUT" == "true" ]]; then
      cat "$response_file"
    fi
  else
    echo "rpc_error=$(tr '\n' ' ' <"$error_file" | sed 's/[[:space:]]\\+/ /g' | sed 's/ $//')"
  fi

  echo

  if (( COUNT > 0 && iteration >= COUNT )); then
    break
  fi

  sleep "$INTERVAL_SECONDS"
done
