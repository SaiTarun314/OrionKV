#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

JAR_PATH="${JAR_PATH:-target/orionkv-0.0.1-SNAPSHOT.jar}"
NODE_IDS="${NODE_IDS:-1 2 3 4 5 6}"

BASE_HTTP_PORT="${BASE_HTTP_PORT:-8080}"
BASE_GRPC_PORT="${BASE_GRPC_PORT:-9090}"

GOSSIP_INTERVAL_MS="${GOSSIP_INTERVAL_MS:-1000}"
SELF_HEARTBEAT_INTERVAL_MS="${SELF_HEARTBEAT_INTERVAL_MS:-500}"
FAILURE_DETECTION_INTERVAL_MS="${FAILURE_DETECTION_INTERVAL_MS:-1000}"
SUSPECT_TIMEOUT_MS="${SUSPECT_TIMEOUT_MS:-15000}"
DEAD_TIMEOUT_MS="${DEAD_TIMEOUT_MS:-45000}"

mkdir -p logs pids

if [[ ! -f "$JAR_PATH" ]]; then
  echo "Jar not found at '$JAR_PATH'. Build first:"
  echo "  mvn clean package -DskipTests"
  exit 1
fi

wait_for_port() {
  local host="$1"
  local port="$2"
  local attempts="${3:-40}"
  local sleep_secs="${4:-0.25}"
  for ((i=1; i<=attempts; i++)); do
    if (echo >"/dev/tcp/${host}/${port}") >/dev/null 2>&1; then
      return 0
    fi
    sleep "$sleep_secs"
  done
  return 1
}

for node in $NODE_IDS; do
  pid_file="pids/node-${node}.pid"
  if [[ -f "$pid_file" ]]; then
    pid="$(cat "$pid_file")"
    if ps -p "$pid" >/dev/null 2>&1; then
      echo "node-${node} already running (pid=$pid), skipping."
      continue
    fi
    rm -f "$pid_file"
  fi

  http_port=$((BASE_HTTP_PORT + node))
  grpc_port=$((BASE_GRPC_PORT + node))
  log_file="logs/node-${node}.log"

  args=(
    "--server.port=${http_port}"
    "--node.node-id=node-${node}"
    "--node.address=127.0.0.1:${grpc_port}"
    "--node.gossip-interval-ms=${GOSSIP_INTERVAL_MS}"
    "--node.self-heartbeat-interval-ms=${SELF_HEARTBEAT_INTERVAL_MS}"
    "--node.failure-detection-interval-ms=${FAILURE_DETECTION_INTERVAL_MS}"
    "--node.suspect-timeout-ms=${SUSPECT_TIMEOUT_MS}"
    "--node.dead-timeout-ms=${DEAD_TIMEOUT_MS}"
  )

  if [[ "$node" != "1" ]]; then
    args+=("--node.seed-address=127.0.0.1:$((BASE_GRPC_PORT + 1))")
  fi

  nohup java -jar "$JAR_PATH" "${args[@]}" >"$log_file" 2>&1 &
  echo $! >"$pid_file"
  echo "started node-${node} grpc=127.0.0.1:${grpc_port} http=${http_port} pid=$(cat "$pid_file")"

  # Prevent join-race failures: ensure seed gRPC is reachable before launching non-seed nodes.
  if [[ "$node" == "1" ]]; then
    if ! wait_for_port "127.0.0.1" "$grpc_port"; then
      echo "node-1 gRPC port ${grpc_port} did not open in time"
      exit 1
    fi
  fi
done

echo "Cluster startup complete."