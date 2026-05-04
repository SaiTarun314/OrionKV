# OrionKV

OrionKV is a distributed key-value store with:

- a **control plane** for membership, gossip, failure detection, consistent hashing, join, leave, and rebalance
- a **coordination plane** for quorum reads and writes, replica routing, merge logic, and repair
- a **data plane** for local storage, persistence, replica application, and range transfer
- a separate **client-router** application that acts as the client-facing HTTP entrypoint and maintains a refreshable view of alive OrionKV nodes

This repository contains both the OrionKV node runtime and the client-router runtime.

## Components

### OrionKV node

Main class:

- `src/main/java/com/orionkv/NodeApplication.java`

Artifact:

- `target/orionkv-0.0.1-SNAPSHOT.jar`

Responsibilities:

- gossip membership
- failure detection
- consistent hashing with virtual nodes
- join / leave / failure rebalance
- quorum read / write coordination
- local persistence and replica serving

### Client router

Main class:

- `src/main/java/com/orionkv/clientrouter/ClientRouterApplication.java`

Artifact:

- `target/orionkv-0.0.1-SNAPSHOT-client-router.jar`

Responsibilities:

- maintain a registry of alive node gRPC addresses
- provide seed resolution for joining nodes
- refresh the node table from a seed node
- proxy client `PUT`, `GET`, and `DELETE` requests to the cluster

## High-Level Architecture

```text
                    +--------------------------------------+
                    |            Client Router             |
                    |--------------------------------------|
                    | seed resolution | registry refresh   |
                    | routed client HTTP requests          |
                    +-------------------+------------------+
                                        |
                                        v
                +--------------------------------------------------+
                |                 OrionKV Node                     |
                +--------------------------------------------------+
                | Coordination Plane | Control Plane | Data Plane  |
                |--------------------------------------------------|
                | quorum routing     | gossip        | local KV     |
                | read/write merge   | FD            | WAL/persist  |
                | replica selection  | hash ring     | replica RPC  |
                | read repair        | rebalance     | range stream  |
                +--------------------------------------------------+
```

## Repository Layout

Core source:

- `src/main/java/com/orionkv/controlplane`
- `src/main/java/com/orionkv/coordinationplane`
- `src/main/java/com/orionkv/dataplane`
- `src/main/java/com/orionkv/clientrouter`
- `src/main/java/com/orionkv/config`
- `src/main/proto/controlplane.proto`
- `src/main/proto/coordination.proto`

Tests:

- `src/test/java/com/orionkv`

Operational notes:

- [ORIONKV_DEVELOPMENT_ISSUES.md](/Users/saitarun/Desktop/ADS/Project/ORIONKV_DEVELOPMENT_ISSUES.md)

Operational scripts:

- `scripts/start-cluster.sh`
- `scripts/kill-all.sh`
- `scripts/docker-cluster-reset.sh`
- `scripts/docker-cluster-restart.sh`
- `scripts/docker-cluster-smoke.sh`
- `scripts/generate-docker-compose.sh`
- `scripts/fair-cluster-load.sh`
- `scripts/docker-balance-audit.sh`
- `scripts/latency-benchmark.sh`
- `scripts/render-latency-results.sh`
- `scripts/render-theoretical-latency-results.sh`
- `scripts/plot-latency-benchmark.py`
- `scripts/watch-grpc-key.sh`
- `scripts/ubuntu-docker-e2e.sh`

## Build

Run tests:

```bash
mvn clean test
```

Package both applications:

```bash
mvn clean package -DskipTests
```

Produced artifacts:

- `target/orionkv-0.0.1-SNAPSHOT.jar`
- `target/orionkv-0.0.1-SNAPSHOT-client-router.jar`

## Recommended Local Development Flow

### 1. Build

```bash
mvn clean package -DskipTests
```

### 2. Start the client router

```bash
java -jar target/orionkv-0.0.1-SNAPSHOT-client-router.jar \
  --server.port=8090 \
  --client.router.registry-path=data/client-router-nodes.json \
  --client.router.refresh-interval-ms=5000 \
  --client.router.rpc-timeout-ms=3000
```

### 3. Start a local OrionKV cluster

```bash
./scripts/start-cluster.sh
```

This starts a local 6-node cluster and configures each node to use the client router base URL.

### 4. Stop local nodes

```bash
./scripts/kill-all.sh
```

## Client Router Operational Flow

The client router is part of the normal cluster lifecycle, not just an optional client proxy.

### Seed resolution for the first node in a brand-new cluster

```bash
curl -X POST http://127.0.0.1:8090/client/nodes/seed \
  -H 'Content-Type: application/json' \
  -d '{"nodeId":"node-1","grpcAddress":"10.0.0.11:9091"}'
```

If the router registry is empty, the response points the node back to itself as seed and stores it.

### Seed resolution for later joining nodes

```bash
curl -X POST http://127.0.0.1:8090/client/nodes/seed \
  -H 'Content-Type: application/json' \
  -d '{"nodeId":"node-2","grpcAddress":"10.0.0.12:9092"}'
```

The router returns an already known alive node as the seed target.

### Confirm a completed join

After the node has joined and bootstrap has completed:

```bash
curl -X POST http://127.0.0.1:8090/client/nodes/confirm \
  -H 'Content-Type: application/json' \
  -d '{"joiningNodeId":"node-2","seedGrpcAddress":"10.0.0.11:9091"}'
```

### Force a manual router refresh

```bash
curl -X POST http://127.0.0.1:8090/client/nodes/refresh \
  -H 'Content-Type: application/json' \
  -d '{"seedGrpcAddress":"10.0.0.11:9091"}'
```

### Inspect the router registry

```bash
curl http://127.0.0.1:8090/client/nodes
```

### Manually register a node in the router table

```bash
curl -X POST http://127.0.0.1:8090/client/nodes/register \
  -H 'Content-Type: application/json' \
  -d '{"nodeId":"node-9","grpcAddress":"10.0.0.19:9099"}'
```

### Routed client operations

Put:

```bash
curl -X PUT http://127.0.0.1:8090/client/kv/user-1 \
  -H 'Content-Type: application/json' \
  -d '{"value":"alice"}'
```

Get:

```bash
curl http://127.0.0.1:8090/client/kv/user-1
```

Delete:

```bash
curl -X DELETE http://127.0.0.1:8090/client/kv/user-1 \
  -H 'Content-Type: application/json' \
  -d '{"timestamp":2000}'
```

## Local Inspection and Debugging

Check membership across local nodes:

```bash
for p in 9091 9092 9093 9094 9095 9096; do
  echo "=== $p ==="
  grpcurl -plaintext -d '{}' -proto src/main/proto/controlplane.proto \
    127.0.0.1:$p orionkv.node.ClusterRpc/GetMembership
done
```

Direct coordination RPC write:

```bash
grpcurl -plaintext -d '{"requestId":"r1","key":"alpha","value":"v1","timestamp":"0"}' \
  -proto src/main/proto/coordination.proto \
  127.0.0.1:9091 orionkv.node.CoordinationRpc/Put
```

Direct coordination RPC read:

```bash
grpcurl -plaintext -d '{"requestId":"r2","key":"alpha"}' \
  -proto src/main/proto/coordination.proto \
  127.0.0.1:9092 orionkv.node.CoordinationRpc/Get
```

Direct replica read:

```bash
grpcurl -plaintext -d '{"requestId":"r3","key":"alpha"}' \
  -proto src/main/proto/coordination.proto \
  127.0.0.1:9093 orionkv.node.ReplicaDataRpc/GetReplica
```

Cluster summary over HTTP:

```bash
curl http://127.0.0.1:8081/internal/cluster/summary
```

The cluster summary endpoint returns an aggregated cluster view built from alive nodes, including:

- quick facts such as active node count, responding node count, total records, and average records per node
- balance information such as minimum / maximum records and spread across nodes
- node-by-node distribution data for replica storage counts

## Docker and Multi-Node Flows

Build the image:

```bash
docker build -t orionkv:local .
```

Restart a generated Docker cluster:

```bash
HOST_IP=127.0.0.1 \
SEED_HOST_IP=127.0.0.1 \
./scripts/docker-cluster-restart.sh
```

Reset Docker cluster state:

```bash
./scripts/docker-cluster-reset.sh
```

Smoke-test the Docker cluster:

```bash
./scripts/docker-cluster-smoke.sh
```

For Ubuntu / VCL flows, use:

- `scripts/setup-ubuntu-host.sh`
- `scripts/ubuntu-docker-e2e.sh`

## Benchmarking and Reporting

Run a benchmark:

```bash
bash scripts/latency-benchmark.sh
```

Generate an HTML report from an existing raw or summary CSV:

```bash
bash scripts/render-latency-results.sh results/<run>.csv
```

Generate a theoretical/modelled report from the same measured run:

```bash
bash scripts/render-theoretical-latency-results.sh results/<run>.csv
```

Artifacts:

- raw CSV
- summary CSV
- HTML report

## Configuration

### Node (`node.*`)

- `node.node-id`
- `node.address`
- `node.bind-port`
- `node.seed-address`
- `node.client-router-base-url`
- `node.gossip-interval-ms` default `5000`
- `node.self-heartbeat-interval-ms` default `1000`
- `node.failure-detection-interval-ms` default `2000`
- `node.suspect-timeout-ms` default `10000`
- `node.dead-timeout-ms` default `30000`
- `node.virtual-node-count` default `512`
- `node.replication-factor` default `3`
- `node.write-quorum` default `2`
- `node.read-quorum` default `2`

### Client router (`client.router.*`)

- `client.router.registry-path` default `data/client-router-nodes.json`
- `client.router.refresh-interval-ms` default `5000`
- `client.router.rpc-timeout-ms` default `3000`
- `client.router.join-confirmation-attempts` default `20`
- `client.router.join-confirmation-delay-ms` default `1000`

### Data plane (`dataplane.*`)

- `dataplane.storage.log-path` default `data/wal.log`

## Exposed APIs

### Control-plane gRPC

- `GossipRpc.Gossip(GossipPayload) -> MembershipState`
- `ClusterRpc.Join(JoinNodeRequest) -> MembershipState`
- `ClusterRpc.GetMembership(google.protobuf.Empty) -> MembershipState`

### Coordination and replica-data gRPC

- `CoordinationRpc.Put(ClientPutRequest) -> ClientPutResponse`
- `CoordinationRpc.Get(ClientGetRequest) -> ClientGetResponse`
- `ReplicaDataRpc.PutReplica(ReplicaPutRequest) -> ReplicaPutResponse`
- `ReplicaDataRpc.GetReplica(ReplicaGetRequest) -> ReplicaGetResponse`

### Client-router HTTP

- `POST /client/nodes/register`
- `POST /client/nodes/seed`
- `POST /client/nodes/confirm`
- `POST /client/nodes/refresh`
- `GET /client/nodes`
- `PUT /client/kv/{key}`
- `GET /client/kv/{key}`
- `DELETE /client/kv/{key}`

### Node HTTP

- `PUT /api/kv/{key}`
- `GET /api/kv/{key}`
- `DELETE /api/kv/{key}?timestamp=...`
- `GET /internal/cluster/summary`
- `GET /internal/storage/range?startToken=...&endToken=...`
- `POST /internal/replica/put`
- `POST /internal/replica/apply-batch`
- `GET /internal/replica/stream?startToken=...&endToken=...`

## Current Status

- membership, gossip, failure detection, ring convergence, quorum operations, and rebalance flows are implemented
- client-router integration is part of the main operational path
- Docker-based multi-node deployment and audit tooling are present
- latency benchmarking and HTML reporting flows are included in the repo
