# OrionKV Node

OrionKV is a single node application that contains all three planes:

- **Control Plane**: membership, gossip, failure detection, ring lifecycle, bootstrap
- **Data Plane**: local KV engine, persistence, replication writes, range streaming
- **Coordination Plane**: routing + quorum decisions + retry/timeout orchestration

Kubernetes is intentionally out of scope in this document.

## Current Code Structure

- `src/main/java/com/orionkv/NodeApplication.java`
- `src/main/java/com/orionkv/config`
- `src/main/java/com/orionkv/controlplane/membership` control-plane membership services and gRPC handlers
- `src/main/java/com/orionkv/controlplane/ring` control-plane consistent hashing and vnode services
- `src/main/java/com/orionkv/controlplane/bootstrap` control-plane join/rebalance services and gRPC handlers
- `src/main/java/com/orionkv/dataplane` data-plane storage/controllers/models
- `src/main/java/com/orionkv/coordinationplane` coordination-plane routing/quorum services
- `src/main/proto/controlplane.proto` control-plane gRPC contract
- `src/main/proto/coordination.proto` coordination and replica-data gRPC contract

## Build and Test

```bash
mvn clean test
```

Package jar:

```bash
mvn -DskipTests package
```

## Run Context (Local Cluster)

Build once:

```bash
mvn clean package -DskipTests
```

Start 6-node local cluster (gRPC + HTTP):

```bash
./scripts/start-cluster.sh
```

Stop all nodes from pid files:

```bash
./scripts/kill-all.sh
```

Check membership on all nodes:

```bash
for p in 9091 9092 9093 9094 9095 9096; do
  echo "=== $p ==="
  grpcurl -plaintext -d '{}' -proto src/main/proto/controlplane.proto \
    127.0.0.1:$p orionkv.node.ClusterRpc/GetMembership
done
```

Add one more node manually (example: node-7):

```bash
for i in 7; do
  java -jar target/orionkv-0.0.1-SNAPSHOT.jar \
    --server.port=$((8080+i)) --node.node-id=node-$i --node.address=127.0.0.1:$((9090+i)) \
    --node.seed-address=127.0.0.1:9091 \
    --node.gossip-interval-ms=1000 --node.failure-detection-interval-ms=1000 \
    --node.suspect-timeout-ms=5000 --node.dead-timeout-ms=12000 \
    > logs/node-$i.log 2>&1 & echo $! > pids/node-$i.pid
done
```

Failure/leave test (example node-5):

```bash
kill -9 $(cat pids/node-5.pid)
```

With `suspect-timeout-ms=5000` and `dead-timeout-ms=12000`, peers should move:
- `ALIVE -> SUSPECT` around 5s
- `SUSPECT -> DEAD` around 12s

Restart node-5:

```bash
for i in 5; do
  java -jar target/orionkv-0.0.1-SNAPSHOT.jar \
    --server.port=$((8080+i)) --node.node-id=node-$i --node.address=127.0.0.1:$((9090+i)) \
    --node.seed-address=127.0.0.1:9091 \
    --node.gossip-interval-ms=1000 --node.failure-detection-interval-ms=1000 \
    --node.suspect-timeout-ms=5000 --node.dead-timeout-ms=12000 \
    > logs/node-$i.log 2>&1 & echo $! > pids/node-$i.pid
done
```

Useful logs:
- `logs/node-*.log`

## Configuration

### Node (`node.*`)

- `node.node-id`
- `node.address`
- `node.seed-address`
- `node.gossip-interval-ms` (default `5000`)
- `node.self-heartbeat-interval-ms` (default `1000`)
- `node.failure-detection-interval-ms` (default `2000`)
- `node.suspect-timeout-ms` (default `10000`)
- `node.dead-timeout-ms` (default `30000`)
- `node.virtual-node-count` (default `32`)
- `node.replication-factor` (default `3`)
- `node.write-quorum` (default `2`)
- `node.read-quorum` (default `2`)

### Data Plane (`dataplane.*`)

- `dataplane.storage.log-path` (default `data/wal.log`)

## Exposed APIs

### Control Plane (gRPC)

From `controlplane.proto`:

- `GossipRpc.Gossip(GossipPayload) -> MembershipState`
- `ClusterRpc.Join(JoinNodeRequest) -> MembershipState`
- `ClusterRpc.GetMembership(google.protobuf.Empty) -> MembershipState`

### Coordination and Replica Data (gRPC)

From `coordination.proto`:

- `CoordinationRpc.Put(ClientPutRequest) -> ClientPutResponse`
- `CoordinationRpc.Get(ClientGetRequest) -> ClientGetResponse`
- `ReplicaDataRpc.PutReplica(ReplicaPutRequest) -> ReplicaPutResponse`
- `ReplicaDataRpc.GetReplica(ReplicaGetRequest) -> ReplicaGetResponse`

### Data Plane (HTTP)

- `PUT /api/kv/{key}`
- `GET /api/kv/{key}`
- `DELETE /api/kv/{key}?timestamp=...`
- `GET /internal/storage/range?startToken=...&endToken=...`
- `POST /internal/replica/put`
- `POST /internal/replica/apply-batch`
- `GET /internal/replica/stream?startToken=...&endToken=...`

## Testing Scenarios

### Coordination Quorum Tests

1. Write quorum success (`N=3, W=2`): with 3 healthy replicas, `CoordinationRpc.Put` returns success and `ack_count >= 2`.
2. Write quorum failure: with only one reachable replica, `CoordinationRpc.Put` returns failure and `ack_count < 2`.
3. Read quorum success (`N=3, R=2`): with at least 2 responses, `CoordinationRpc.Get` returns read result from quorum winner.
4. Read quorum failure: with fewer than 2 responses, `CoordinationRpc.Get` returns read-quorum failure.
5. LWW conflict resolution: conflicting replica values return highest timestamp value.
6. LWW tie case: equal timestamps resolve deterministically by node-id ordering.

### Ring and Replication Correctness

1. Deterministic routing: same key routed through different coordinators yields the same replica set.
2. Ring consistency across nodes: all alive nodes produce identical token/replica mapping for sampled keys.
3. Replica write correctness: after `CoordinationRpc.Put`, direct `ReplicaDataRpc.GetReplica` on chosen replicas shows expected value/version.

### Failure and Recovery Behavior

1. Single-replica failure: with one replica down, writes/reads still succeed for `W=2`, `R=2` if two replicas respond.
2. Double-replica failure: with two replicas down, writes/reads fail with quorum-not-met outcomes.
3. Membership transition impact: after node kill, verify `ALIVE -> SUSPECT -> DEAD` and routing continues with alive nodes.
4. Rejoin correctness: after node restart and gossip convergence, reads remain correct for sampled keys.
5. Persistence check: restart nodes after writes and verify values survive via quorum reads.

### gRPC Smoke Commands

Coordination Put:

```bash
grpcurl -plaintext -d '{"requestId":"r1","key":"alpha","value":"v1","timestamp":"0"}' \
  -proto src/main/proto/coordination.proto \
  127.0.0.1:9091 orionkv.node.CoordinationRpc/Put
```

Coordination Get:

```bash
grpcurl -plaintext -d '{"requestId":"r2","key":"alpha"}' \
  -proto src/main/proto/coordination.proto \
  127.0.0.1:9092 orionkv.node.CoordinationRpc/Get
```

Replica Put:

```bash
grpcurl -plaintext -d '{"requestId":"r3","key":"alpha","value":"v1","timestamp":"1710000000000","tombstone":false,"token":"123","sourceNodeId":"node-1"}' \
  -proto src/main/proto/coordination.proto \
  127.0.0.1:9093 orionkv.node.ReplicaDataRpc/PutReplica
```

Replica Get:

```bash
grpcurl -plaintext -d '{"requestId":"r4","key":"alpha"}' \
  -proto src/main/proto/coordination.proto \
  127.0.0.1:9093 orionkv.node.ReplicaDataRpc/GetReplica
```

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
