#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

NODE_COUNT="${NODE_COUNT:-10}"
GRPC_PORT_BASE="${GRPC_PORT_BASE:-19090}"
PROTO_FILE="${PROTO_FILE:-src/main/proto/controlplane.proto}"
SEED_GRPC_PORT="${SEED_GRPC_PORT:-$((GRPC_PORT_BASE + 1))}"

echo "Membership sample from seed on 127.0.0.1:${SEED_GRPC_PORT}"
grpcurl -plaintext -d '{}' -proto "$PROTO_FILE" \
  "127.0.0.1:${SEED_GRPC_PORT}" orionkv.node.ClusterRpc/GetMembership

echo
echo "Per-node membership reachability"
for i in $(seq 1 "$NODE_COUNT"); do
  port=$((GRPC_PORT_BASE + i))
  printf "node-%-3s %s\n" "$i" "$(grpcurl -plaintext -d '{}' -proto "$PROTO_FILE" "127.0.0.1:${port}" orionkv.node.ClusterRpc/GetMembership >/dev/null 2>&1 && echo UP || echo DOWN)"
done
