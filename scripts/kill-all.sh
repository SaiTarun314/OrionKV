#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PID_DIR="${PID_DIR:-$ROOT_DIR/pids}"
SIGNAL="${SIGNAL:-TERM}"

if [[ ! -d "$PID_DIR" ]]; then
  echo "PID directory not found: $PID_DIR"
  exit 0
fi

shopt -s nullglob
pid_files=("$PID_DIR"/*.pid)
shopt -u nullglob

if [[ ${#pid_files[@]} -eq 0 ]]; then
  echo "No pid files found in $PID_DIR"
  exit 0
fi

for pid_file in "${pid_files[@]}"; do
  if [[ ! -s "$pid_file" ]]; then
    echo "Skipping empty pid file: $pid_file"
    rm -f "$pid_file"
    continue
  fi

  pid="$(tr -d '[:space:]' < "$pid_file")"
  name="$(basename "$pid_file")"

  if [[ -z "$pid" || ! "$pid" =~ ^[0-9]+$ ]]; then
    echo "Skipping invalid pid in $name: '$pid'"
    rm -f "$pid_file"
    continue
  fi

  if ps -p "$pid" >/dev/null 2>&1; then
    kill "-$SIGNAL" "$pid" >/dev/null 2>&1 || true
    echo "Sent $SIGNAL to pid=$pid ($name)"
  else
    echo "Process not running for pid=$pid ($name)"
  fi

  rm -f "$pid_file"
done

echo "Done."
