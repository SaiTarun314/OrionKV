#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$ROOT_DIR"

COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.generated.yml}"
WIPE_DATA="${WIPE_DATA:-true}"
WIPE_IMAGE="${WIPE_IMAGE:-false}"
IMAGE_NAME="${IMAGE_NAME:-orionkv:local}"
WIPE_LOCAL_STATE="${WIPE_LOCAL_STATE:-false}"
SUDO_DOCKER="${SUDO_DOCKER:-false}"

run_docker() {
  if [[ "$SUDO_DOCKER" == "true" ]] && command -v sudo >/dev/null 2>&1; then
    sudo docker "$@"
    return
  fi
  docker "$@"
}

compose_cmd() {
  if run_docker compose version >/dev/null 2>&1; then
    run_docker compose "$@"
    return
  fi
  if command -v docker-compose >/dev/null 2>&1; then
    if [[ "$SUDO_DOCKER" == "true" ]] && command -v sudo >/dev/null 2>&1; then
      sudo docker-compose "$@"
    else
      docker-compose "$@"
    fi
    return
  fi
  echo "Neither 'docker compose' nor 'docker-compose' is available" >&2
  exit 1
}

run_with_optional_sudo() {
  if "$@"; then
    return 0
  fi
  local status=$?
  if command -v sudo >/dev/null 2>&1; then
    echo "Command failed without sudo, retrying with sudo: $*" >&2
    sudo "$@"
    return 0
  fi
  return "$status"
}

echo "Stopping Docker cluster if it exists"
if [[ -f "$COMPOSE_FILE" ]]; then
  compose_cmd -f "$COMPOSE_FILE" down --remove-orphans >/dev/null 2>&1 || true
fi

if [[ "$WIPE_DATA" == "true" ]]; then
  run_with_optional_sudo rm -rf docker-data
  echo "Removed docker-data/"
fi

run_with_optional_sudo rm -f docker-compose.generated.yml
echo "Removed docker-compose.generated.yml"

if [[ "$WIPE_LOCAL_STATE" == "true" ]]; then
  run_with_optional_sudo rm -rf data logs pids
  echo "Removed local data/logs/pids"
fi

if [[ "$WIPE_IMAGE" == "true" ]]; then
  run_docker image rm -f "$IMAGE_NAME" >/dev/null 2>&1 || true
  echo "Removed image $IMAGE_NAME"
fi

echo "Docker cluster reset complete."
