#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$ROOT_DIR"

NODE_COUNT="${NODE_COUNT:-25}"
VIRTUAL_NODE_COUNT="${VIRTUAL_NODE_COUNT:-128}"
REPLICATION_FACTOR="${REPLICATION_FACTOR:-3}"
WRITE_QUORUM="${WRITE_QUORUM:-2}"
READ_QUORUM="${READ_QUORUM:-2}"
JAVA_OPTS="${JAVA_OPTS:--Xms16m -Xmx96m}"
IMAGE_NAME="${IMAGE_NAME:-orionkv:local}"
WAIT_SECONDS="${WAIT_SECONDS:-20}"
REBUILD_IMAGE="${REBUILD_IMAGE:-true}"
WIPE_DATA="${WIPE_DATA:-true}"

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

WIPE_DATA="$WIPE_DATA" WIPE_IMAGE="false" ./scripts/docker-cluster-reset.sh

if [[ "$REBUILD_IMAGE" == "true" ]]; then
  docker build -t "$IMAGE_NAME" .
fi

NODE_COUNT="$NODE_COUNT" \
VIRTUAL_NODE_COUNT="$VIRTUAL_NODE_COUNT" \
REPLICATION_FACTOR="$REPLICATION_FACTOR" \
WRITE_QUORUM="$WRITE_QUORUM" \
READ_QUORUM="$READ_QUORUM" \
JAVA_OPTS="$JAVA_OPTS" \
IMAGE_NAME="$IMAGE_NAME" \
./scripts/generate-docker-compose.sh

compose_cmd -f docker-compose.generated.yml up -d

echo "Waiting ${WAIT_SECONDS}s for startup"
sleep "$WAIT_SECONDS"

NODE_COUNT="$NODE_COUNT" ./scripts/docker-cluster-smoke.sh
