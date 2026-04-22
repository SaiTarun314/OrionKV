#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$ROOT_DIR"

COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.generated.yml}"
REMOVE_ORPHANS="${REMOVE_ORPHANS:-true}"

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

if [[ ! -f "$COMPOSE_FILE" ]]; then
  echo "Compose file not found: $COMPOSE_FILE"
  exit 1
fi

args=(-f "$COMPOSE_FILE" down)
if [[ "$REMOVE_ORPHANS" == "true" ]]; then
  args+=(--remove-orphans)
fi

compose_cmd "${args[@]}"
echo "Docker cluster stopped."
