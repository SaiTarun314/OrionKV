#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_PATH="${BASH_SOURCE[0]:-$0}"
ROOT_DIR="$(cd "$(dirname "${SCRIPT_PATH}")/.." && pwd -P)"
cd "$ROOT_DIR"

MODE="${MODE:-quorum}"
NODE_COUNT="${NODE_COUNT:-25}"
NODE_ID_OFFSET="${NODE_ID_OFFSET:-0}"
HOST_IP="${HOST_IP:-127.0.0.1}"
SEED_HOST_IP="${SEED_HOST_IP:-$HOST_IP}"
SEED_NODE_ID="${SEED_NODE_ID:-1}"
IMAGE_NAME="${IMAGE_NAME:-orionkv:local}"
WAIT_SECONDS="${WAIT_SECONDS:-20}"
SUDO_DOCKER="${SUDO_DOCKER:-false}"
CLIENT_BASE_URL="${CLIENT_BASE_URL:-http://152.7.177.154:8090}"
CLIENT_REFRESH_SEED_GRPC_ADDRESS="${CLIENT_REFRESH_SEED_GRPC_ADDRESS:-}"
CLIENT_RESTART_URL="${CLIENT_RESTART_URL:-http://152.7.177.154:8090/client/admin/restart}"
CLIENT_RESTART_METHOD="${CLIENT_RESTART_METHOD:-POST}"
CLIENT_RESTART_BODY="${CLIENT_RESTART_BODY:-{\"clearRegistry\":true,\"delayMs\":250}}"
CLIENT_RESTART_REQUIRED="${CLIENT_RESTART_REQUIRED:-false}"
CLIENT_RESTART_WAIT_SECONDS="${CLIENT_RESTART_WAIT_SECONDS:-20}"
REPLICATION_FACTOR="${REPLICATION_FACTOR:-3}"
WRITE_QUORUM="${WRITE_QUORUM:-2}"
READ_QUORUM="${READ_QUORUM:-2}"
QUORUM_CONFIGS="${QUORUM_CONFIGS:-1:1,2:2,3:3}"
REPLICATION_FACTORS="${REPLICATION_FACTORS:-2,3,4,5}"
REPLICATION_QUORUM_POLICY="${REPLICATION_QUORUM_POLICY:-majority}"
SAMPLES="${SAMPLES:-30}"
WARMUP_SAMPLES="${WARMUP_SAMPLES:-5}"
VALUE_SIZE_BYTES="${VALUE_SIZE_BYTES:-128}"
RESULTS_DIR="${RESULTS_DIR:-results}"
RESULTS_BASENAME="${RESULTS_BASENAME:-latency-benchmark-$(date +%Y%m%d-%H%M%S)}"
REBUILD_IMAGE="${REBUILD_IMAGE:-true}"

RAW_RESULTS_FILE="${RESULTS_DIR}/${RESULTS_BASENAME}.csv"
SUMMARY_RESULTS_FILE="${RESULTS_DIR}/${RESULTS_BASENAME}-summary.csv"

if [[ -z "$CLIENT_REFRESH_SEED_GRPC_ADDRESS" ]]; then
  CLIENT_REFRESH_SEED_GRPC_ADDRESS="${HOST_IP}:$((19090 + NODE_ID_OFFSET + 1))"
fi

usage() {
  cat <<'EOF'
Usage:
  ./scripts/latency-benchmark.sh

Modes:
  MODE=quorum
    Uses fixed replication factor and varies W:R using QUORUM_CONFIGS.

  MODE=replication
    Uses REPLICATION_FACTORS and derives W/R per factor using REPLICATION_QUORUM_POLICY.

Required environment for single-VCL run:
  HOST_IP=<this-vcl-ip>

Optional client reset:
  CLIENT_RESTART_URL=http://152.7.177.154:8090/client/admin/restart
  CLIENT_RESTART_METHOD=POST
  CLIENT_RESTART_BODY='{"clearRegistry":true,"delayMs":250}'
  CLIENT_RESTART_REQUIRED=true

Examples:
  MODE=quorum \
  HOST_IP=152.7.178.169 \
  SEED_HOST_IP=152.7.178.169 \
  SUDO_DOCKER=true \
  CLIENT_BASE_URL=http://152.7.177.154:8090 \
  QUORUM_CONFIGS=1:1,2:2,3:3 \
  ./scripts/latency-benchmark.sh

  MODE=replication \
  HOST_IP=152.7.178.169 \
  SEED_HOST_IP=152.7.178.169 \
  SUDO_DOCKER=true \
  CLIENT_BASE_URL=http://152.7.177.154:8090 \
  REPLICATION_FACTORS=2,3,4,5 \
  ./scripts/latency-benchmark.sh
EOF
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

require_cmd curl
require_cmd python3

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

if ! [[ "$NODE_COUNT" =~ ^[0-9]+$ ]] || (( NODE_COUNT < 1 )); then
  echo "NODE_COUNT must be a positive integer" >&2
  exit 1
fi

if ! [[ "$SAMPLES" =~ ^[0-9]+$ ]] || (( SAMPLES < 1 )); then
  echo "SAMPLES must be a positive integer" >&2
  exit 1
fi

if ! [[ "$WARMUP_SAMPLES" =~ ^[0-9]+$ ]]; then
  echo "WARMUP_SAMPLES must be a non-negative integer" >&2
  exit 1
fi

if ! [[ "$VALUE_SIZE_BYTES" =~ ^[0-9]+$ ]] || (( VALUE_SIZE_BYTES < 1 )); then
  echo "VALUE_SIZE_BYTES must be a positive integer" >&2
  exit 1
fi

if [[ "$MODE" != "quorum" && "$MODE" != "replication" ]]; then
  echo "MODE must be 'quorum' or 'replication'" >&2
  exit 1
fi

mkdir -p "$RESULTS_DIR"

VALUE_PAYLOAD="$(python3 - "$VALUE_SIZE_BYTES" <<'PY'
import sys
size = int(sys.argv[1])
print("v" * size)
PY
)"

echo "mode,setting,replication_factor,write_quorum,read_quorum,operation,sample_index,latency_ms,http_status" >"$RAW_RESULTS_FILE"

client_restart() {
  if [[ -z "$CLIENT_RESTART_URL" ]]; then
    if [[ "$CLIENT_RESTART_REQUIRED" == "true" ]]; then
      echo "CLIENT_RESTART_REQUIRED=true but CLIENT_RESTART_URL is not set" >&2
      exit 1
    fi
    echo "==> No client restart URL configured; skipping client restart"
    return
  fi

  echo "==> Restarting client via ${CLIENT_RESTART_METHOD} ${CLIENT_RESTART_URL}"
  local curl_args=(-sS -X "$CLIENT_RESTART_METHOD" "$CLIENT_RESTART_URL")
  if [[ -n "$CLIENT_RESTART_BODY" ]]; then
    curl_args+=(-H "Content-Type: application/json" -d "$CLIENT_RESTART_BODY")
  fi
  curl "${curl_args[@]}" >/dev/null
  sleep "$CLIENT_RESTART_WAIT_SECONDS"
}

refresh_client_router() {
  echo "==> Refreshing client-router using seed ${CLIENT_REFRESH_SEED_GRPC_ADDRESS}"
  curl -fsS -X POST "${CLIENT_BASE_URL}/client/nodes/refresh" \
    -H "Content-Type: application/json" \
    -d "{\"seedGrpcAddress\":\"${CLIENT_REFRESH_SEED_GRPC_ADDRESS}\"}" >/dev/null
}

wait_for_client_router_count() {
  local expected_count="$1"
  local timeout_seconds="${2:-60}"
  local deadline=$(( $(date +%s) + timeout_seconds ))

  while (( $(date +%s) < deadline )); do
    local payload
    if payload="$(curl -fsS "${CLIENT_BASE_URL}/client/nodes" 2>/dev/null)"; then
      local alive_count
      alive_count="$(python3 - <<'PY' <<<"$payload"
import json
import sys

data = json.load(sys.stdin)
nodes = data.get("nodes", [])
alive = sum(1 for node in nodes if node.get("status") == "ALIVE")
print(alive)
PY
)"
      if [[ "$alive_count" == "$expected_count" ]]; then
        return 0
      fi
    fi
    sleep 2
  done

  return 1
}

restart_cluster() {
  local replication_factor="$1"
  local write_quorum="$2"
  local read_quorum="$3"

  echo "==> Restarting 25-node single-VCL cluster with N=${replication_factor}, W=${write_quorum}, R=${read_quorum}"
  NODE_COUNT="$NODE_COUNT" \
  NODE_ID_OFFSET="$NODE_ID_OFFSET" \
  HOST_IP="$HOST_IP" \
  SEED_HOST_IP="$SEED_HOST_IP" \
  SEED_NODE_ID="$SEED_NODE_ID" \
  IMAGE_NAME="$IMAGE_NAME" \
  WAIT_SECONDS="$WAIT_SECONDS" \
  REBUILD_IMAGE="$REBUILD_IMAGE" \
  SUDO_DOCKER="$SUDO_DOCKER" \
  REPLICATION_FACTOR="$replication_factor" \
  WRITE_QUORUM="$write_quorum" \
  READ_QUORUM="$read_quorum" \
  ./scripts/docker-cluster-restart.sh

  refresh_client_router

  echo "==> Waiting for client-router to see ${NODE_COUNT} alive nodes"
  if ! wait_for_client_router_count "$NODE_COUNT" 120; then
    echo "client-router did not observe ${NODE_COUNT} alive nodes in time" >&2
    curl -fsS "${CLIENT_BASE_URL}/client/nodes" || true
    exit 1
  fi
}

curl_timing() {
  local method="$1"
  local url="$2"
  local body="${3:-}"
  local output

  if [[ "$method" == "GET" ]]; then
    output="$(curl -sS -o /dev/null -w '%{http_code} %{time_total}' "$url")"
  else
    output="$(curl -sS -o /dev/null -w '%{http_code} %{time_total}' -X "$method" \
      -H "Content-Type: application/json" -d "$body" "$url")"
  fi

  local http_status latency_seconds
  read -r http_status latency_seconds <<<"$output"
  local latency_ms
  latency_ms="$(python3 - "$latency_seconds" <<'PY'
import sys
print(f"{float(sys.argv[1]) * 1000:.3f}")
PY
)"
  printf '%s,%s\n' "$http_status" "$latency_ms"
}

record_sample() {
  local mode_name="$1"
  local setting="$2"
  local replication_factor="$3"
  local write_quorum="$4"
  local read_quorum="$5"
  local operation="$6"
  local sample_index="$7"
  local latency_ms="$8"
  local http_status="$9"

  echo "${mode_name},${setting},${replication_factor},${write_quorum},${read_quorum},${operation},${sample_index},${latency_ms},${http_status}" >>"$RAW_RESULTS_FILE"
}

benchmark_current_cluster() {
  local mode_name="$1"
  local setting="$2"
  local replication_factor="$3"
  local write_quorum="$4"
  local read_quorum="$5"
  local key_prefix="bench-${mode_name}-${setting//:/-}-$(date +%s)"
  local total_iterations=$(( WARMUP_SAMPLES + SAMPLES ))
  local i

  echo "==> Running warmup=${WARMUP_SAMPLES}, samples=${SAMPLES} for setting=${setting}"

  for i in $(seq 1 "$total_iterations"); do
    local key="${key_prefix}-${i}"
    local timestamp_ms
    timestamp_ms="$(python3 - <<'PY'
import time
print(int(time.time() * 1000))
PY
)"
    local put_result put_status put_latency_ms
    put_result="$(curl_timing "PUT" "${CLIENT_BASE_URL}/client/kv/${key}" "{\"value\":\"${VALUE_PAYLOAD}\",\"timestamp\":${timestamp_ms}}")"
    put_status="${put_result%%,*}"
    put_latency_ms="${put_result##*,}"

    local get_result get_status get_latency_ms
    get_result="$(curl_timing "GET" "${CLIENT_BASE_URL}/client/kv/${key}")"
    get_status="${get_result%%,*}"
    get_latency_ms="${get_result##*,}"

    if (( i > WARMUP_SAMPLES )); then
      local sample_index=$(( i - WARMUP_SAMPLES ))
      record_sample "$mode_name" "$setting" "$replication_factor" "$write_quorum" "$read_quorum" "put" "$sample_index" "$put_latency_ms" "$put_status"
      record_sample "$mode_name" "$setting" "$replication_factor" "$write_quorum" "$read_quorum" "get" "$sample_index" "$get_latency_ms" "$get_status"
    fi
  done
}

majority_quorum() {
  python3 - "$1" <<'PY'
import math
import sys
n = int(sys.argv[1])
print(math.floor(n / 2) + 1)
PY
}

run_quorum_mode() {
  IFS=',' read -r -a configs <<<"$QUORUM_CONFIGS"
  local config
  for config in "${configs[@]}"; do
    local write_quorum="${config%%:*}"
    local read_quorum="${config##*:}"

    if ! [[ "$write_quorum" =~ ^[0-9]+$ && "$read_quorum" =~ ^[0-9]+$ ]]; then
      echo "Invalid QUORUM_CONFIGS entry: ${config}" >&2
      exit 1
    fi
    if (( write_quorum < 1 || read_quorum < 1 || write_quorum > REPLICATION_FACTOR || read_quorum > REPLICATION_FACTOR )); then
      echo "Invalid quorum config ${config} for replication factor ${REPLICATION_FACTOR}" >&2
      exit 1
    fi

    client_restart
    restart_cluster "$REPLICATION_FACTOR" "$write_quorum" "$read_quorum"
    benchmark_current_cluster "quorum" "W${write_quorum}-R${read_quorum}" "$REPLICATION_FACTOR" "$write_quorum" "$read_quorum"
  done
}

run_replication_mode() {
  IFS=',' read -r -a factors <<<"$REPLICATION_FACTORS"
  local factor
  for factor in "${factors[@]}"; do
    if ! [[ "$factor" =~ ^[0-9]+$ ]] || (( factor < 1 )); then
      echo "Invalid replication factor: ${factor}" >&2
      exit 1
    fi

    local write_quorum read_quorum
    case "$REPLICATION_QUORUM_POLICY" in
      majority)
        write_quorum="$(majority_quorum "$factor")"
        read_quorum="$(majority_quorum "$factor")"
        ;;
      *)
        echo "Unsupported REPLICATION_QUORUM_POLICY=${REPLICATION_QUORUM_POLICY}" >&2
        exit 1
        ;;
    esac

    client_restart
    restart_cluster "$factor" "$write_quorum" "$read_quorum"
    benchmark_current_cluster "replication" "N${factor}" "$factor" "$write_quorum" "$read_quorum"
  done
}

write_summary_csv() {
  python3 - "$RAW_RESULTS_FILE" "$SUMMARY_RESULTS_FILE" <<'PY'
import csv
import math
import sys
from collections import defaultdict

raw_path, summary_path = sys.argv[1], sys.argv[2]
groups = defaultdict(list)

with open(raw_path, newline="") as handle:
    reader = csv.DictReader(handle)
    for row in reader:
        key = (
            row["mode"],
            row["setting"],
            row["replication_factor"],
            row["write_quorum"],
            row["read_quorum"],
            row["operation"],
        )
        groups[key].append(float(row["latency_ms"]))

def percentile(values, pct):
    if not values:
        return 0.0
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, math.ceil((pct / 100.0) * len(ordered)) - 1))
    return ordered[index]

with open(summary_path, "w", newline="") as handle:
    writer = csv.writer(handle)
    writer.writerow([
        "mode", "setting", "replication_factor", "write_quorum", "read_quorum", "operation",
        "sample_count", "avg_latency_ms", "p50_latency_ms", "p95_latency_ms", "p99_latency_ms"
    ])
    for key in sorted(groups):
        values = groups[key]
        avg = sum(values) / len(values)
        writer.writerow([
            *key,
            len(values),
            f"{avg:.3f}",
            f"{percentile(values, 50):.3f}",
            f"{percentile(values, 95):.3f}",
            f"{percentile(values, 99):.3f}",
        ])
PY
}

if [[ "$MODE" == "quorum" ]]; then
  run_quorum_mode
else
  run_replication_mode
fi

write_summary_csv

echo "==> Raw results written to ${RAW_RESULTS_FILE}"
echo "==> Summary results written to ${SUMMARY_RESULTS_FILE}"
