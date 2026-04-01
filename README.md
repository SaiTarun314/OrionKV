# OrionKV Node

OrionKV is a single node application that contains all three planes:

- **Control Plane**: membership, gossip, failure detection, ring lifecycle, bootstrap
- **Data Plane**: local KV engine, persistence, replication writes, range streaming
- **Coordination Plane**: routing + quorum decisions + retry/timeout orchestration

Kubernetes is intentionally out of scope in this document.

## Scope Plan (Team Ownership)

### Week 1

Sai Tarun (Control Plane):

- gossip membership skeleton
- membership table with `ALIVE/SUSPECT/DEAD`
- consistent hash ring
- virtual node token assignment

Smeet (Data Plane):

- local key-value storage engine
- `GET/PUT/DELETE`
- persistent layer (WAL/RocksDB style)
- range scan API

Yash (Coordination Plane):

- client API layer
- request routing
- ring lookup for responsible replicas
- forward reads/writes to replicas

### Week 2

Sai Tarun (Control Plane):

- failure detection via gossip timeouts
- ring reconstruction on membership change
- node join protocol
- token reassignment logic

Smeet (Data Plane):

- replica write handling
- `PUT_REPLICA`
- timestamped versions
- bootstrap streaming for key ranges

Yash (Coordination Plane):

- quorum writes (`N, W`)
- quorum reads (`R`)
- timeout and retry handling
- conflict resolution (last-write-wins)

## Current Code Structure

- `src/main/java/com/orionkv/NodeApplication.java`
- `src/main/java/com/orionkv/config`
- `src/main/java/com/orionkv/controlplane/membership` control-plane membership services and gRPC handlers
- `src/main/java/com/orionkv/controlplane/ring` control-plane consistent hashing and vnode services
- `src/main/java/com/orionkv/controlplane/bootstrap` control-plane join/rebalance services and gRPC handlers
- `src/main/java/com/orionkv/dataplane` data-plane storage/controllers/models
- `src/main/java/com/orionkv/coordinationplane` coordination-plane routing/quorum skeleton
- `src/main/proto/controlplane.proto` control-plane gRPC contract

## Implementation Status

### Control Plane

Implemented:

- gossip-based membership exchange
- membership table with status transitions
- failure detector scheduler
- consistent hash ring with virtual nodes
- join flow and rebalance trigger logic
- gRPC handlers (`GossipRpc`, `ClusterRpc`)

### Data Plane

Implemented:

- local storage engine
- `GET/PUT/DELETE` HTTP API
- WAL persistence + restart recovery
- replica write + batch apply endpoints
- range scan + bootstrap stream endpoints
- deterministic timestamp/tombstone conflict handling

### Coordination Plane

Implemented now as base structure:

- replica routing service wired to hash ring
- quorum evaluation utility (`N/W/R` checks)
- domain models for quorum config and resolved replica route

Still pending for full coordination behavior:

- client-facing coordination API
- remote forwarding to replica nodes
- timeout/retry policy
- merged read conflict resolution path

## Build and Test

```bash
mvn clean test
```

Package jar:

```bash
mvn -DskipTests package
```

## Run Locally

### Data-plane focused run

When `node.node-id` / `node.address` are absent, control-plane gRPC startup is skipped.

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8080"
```

### Full node run (HTTP + gRPC)

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8080 --node.node-id=node-a --node.address=127.0.0.1:9090"
```

Two-node example:

Terminal 1:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8081 --node.node-id=node-a --node.address=127.0.0.1:9091"
```

Terminal 2:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8082 --node.node-id=node-b --node.address=127.0.0.1:9092 --node.seed-address=127.0.0.1:9091"
```

## Configuration

### Node (`node.*`)

- `node.node-id`
- `node.address`
- `node.seed-address`
- `node.gossip-interval-ms` (default `5000`)
- `node.failure-detection-interval-ms` (default `2000`)
- `node.suspect-timeout-ms` (default `10000`)
- `node.dead-timeout-ms` (default `30000`)
- `node.virtual-node-count` (default `32`)
- `node.replication-factor` (default `3`)

### Data Plane (`dataplane.*`)

- `dataplane.storage.log-path` (default `data/wal.log`)

## Exposed APIs

### Control Plane (gRPC)

From `controlplane.proto`:

- `GossipRpc.Gossip(GossipPayload) -> MembershipState`
- `ClusterRpc.Join(JoinNodeRequest) -> MembershipState`
- `ClusterRpc.GetMembership(google.protobuf.Empty) -> MembershipState`

### Data Plane (HTTP)

- `PUT /api/kv/{key}`
- `GET /api/kv/{key}`
- `DELETE /api/kv/{key}?timestamp=...`
- `GET /internal/storage/range?startToken=...&endToken=...`
- `POST /internal/replica/put`
- `POST /internal/replica/apply-batch`
- `GET /internal/replica/stream?startToken=...&endToken=...`

## Docker

Build:

```bash
docker build -t orionkv:local .
```

Run:

```bash
docker run --rm -p 8080:8080 -p 9090:9090 \
  -v "$(pwd)/data:/app/data" \
  -e APP_ARGS="--server.port=8080 --node.node-id=node-a --node.address=0.0.0.0:9090 --dataplane.storage.log-path=/app/data/wal.log" \
  orionkv:local
```
