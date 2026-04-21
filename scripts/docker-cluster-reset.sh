#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$ROOT_DIR"

COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.generated.yml}"
WIPE_DATA="${WIPE_DATA:-true}"
WIPE_IMAGE="${WIPE_IMAGE:-false}"
IMAGE_NAME="${IMAGE_NAME:-orionkv:local}"
WIPE_LOCAL_STATE="${WIPE_LOCAL_STATE:-false}"

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

echo "Stopping Docker cluster if it exists"
if [[ -f "$COMPOSE_FILE" ]]; then
  compose_cmd -f "$COMPOSE_FILE" down --remove-orphans >/dev/null 2>&1 || true
fi

if [[ "$WIPE_DATA" == "true" ]]; then
  rm -rf docker-data
  echo "Removed docker-data/"
fi

rm -f docker-compose.generated.yml
echo "Removed docker-compose.generated.yml"

if [[ "$WIPE_LOCAL_STATE" == "true" ]]; then
  rm -rf data logs pids
  echo "Removed local data/logs/pids"
fi

if [[ "$WIPE_IMAGE" == "true" ]]; then
  docker image rm -f "$IMAGE_NAME" >/dev/null 2>&1 || true
  echo "Removed image $IMAGE_NAME"
fi

echo "Docker cluster reset complete."
