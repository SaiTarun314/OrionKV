#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$ROOT_DIR"

NODE_COUNT="${NODE_COUNT:-25}"
TOTAL_KEYS="${TOTAL_KEYS:-50000}"
CONCURRENCY="${CONCURRENCY:-64}"
VIRTUAL_NODE_COUNT="${VIRTUAL_NODE_COUNT:-128}"
REPLICATION_FACTOR="${REPLICATION_FACTOR:-3}"
WRITE_QUORUM="${WRITE_QUORUM:-2}"
READ_QUORUM="${READ_QUORUM:-2}"
JAVA_OPTS="${JAVA_OPTS:--Xms16m -Xmx96m}"
IMAGE_NAME="${IMAGE_NAME:-orionkv:local}"
COORDINATOR_PORTS="${COORDINATOR_PORTS:-19091 19095 19105 19115}"
SMOKE_NODE_COUNT="${SMOKE_NODE_COUNT:-$NODE_COUNT}"
LOAD_KEY_PREFIX="${LOAD_KEY_PREFIX:-bulk-key}"
LOAD_VALUE_PREFIX="${LOAD_VALUE_PREFIX:-bulk-value}"
SAMPLE_SIZE="${SAMPLE_SIZE:-500}"
WAIT_SECONDS="${WAIT_SECONDS:-20}"

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

echo "==> Building image ${IMAGE_NAME}"
docker build -t "${IMAGE_NAME}" .

echo "==> Resetting previous docker cluster state"
compose_cmd -f docker-compose.generated.yml down --remove-orphans >/dev/null 2>&1 || true
rm -rf docker-data

echo "==> Generating compose for ${NODE_COUNT} nodes"
NODE_COUNT="${NODE_COUNT}" \
VIRTUAL_NODE_COUNT="${VIRTUAL_NODE_COUNT}" \
REPLICATION_FACTOR="${REPLICATION_FACTOR}" \
WRITE_QUORUM="${WRITE_QUORUM}" \
READ_QUORUM="${READ_QUORUM}" \
JAVA_OPTS="${JAVA_OPTS}" \
IMAGE_NAME="${IMAGE_NAME}" \
./scripts/generate-docker-compose.sh

echo "==> Starting docker cluster"
compose_cmd -f docker-compose.generated.yml up -d

echo "==> Waiting ${WAIT_SECONDS}s for gossip/join convergence"
sleep "${WAIT_SECONDS}"

echo "==> Cluster smoke check"
NODE_COUNT="${SMOKE_NODE_COUNT}" ./scripts/docker-cluster-smoke.sh

echo "==> Bulk loading ${TOTAL_KEYS} keys with concurrency ${CONCURRENCY}"
TOTAL_KEYS="${TOTAL_KEYS}" \
CONCURRENCY="${CONCURRENCY}" \
PORTS="${COORDINATOR_PORTS}" \
KEY_PREFIX="${LOAD_KEY_PREFIX}" \
VALUE_PREFIX="${LOAD_VALUE_PREFIX}" \
./scripts/docker-bulk-load.sh

echo "==> Balance and replica audit"
TOTAL_KEYS="${TOTAL_KEYS}" \
SAMPLE_SIZE="${SAMPLE_SIZE}" \
KEY_PREFIX="${LOAD_KEY_PREFIX}" \
COORDINATOR_PORTS="${COORDINATOR_PORTS}" \
./scripts/docker-balance-audit.sh

cat <<EOF

E2E flow complete.

Useful follow-ups:
  compose_cmd -f docker-compose.generated.yml ps
  sudo docker stop orionkv-node-22
  NODE_COUNT=${NODE_COUNT} ./scripts/docker-cluster-smoke.sh
  TOTAL_KEYS=${TOTAL_KEYS} SAMPLE_SIZE=${SAMPLE_SIZE} ./scripts/docker-balance-audit.sh
EOF
