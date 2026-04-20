# OrionKV Live Test Suite (No Automation Scripts)

## 1) Scope
This test suite is designed for **live demonstrations on running servers**, not scripted load harnesses.

Goals:
- validate ring/routing/rebalance behavior in a live cluster
- validate quorum behavior (`N=3, W=2, R=2`) under failures
- validate durability/recovery behavior (WAL + rejoin)
- produce tangible evidence for supervisor review

This is for your current multi-process system, but written in distributed-systems terms so it remains valid when moved to multi-node deployment.

---

## 2) Tangible Evidence Already Available

Current branch validation done:
- Full suite compiles and passes: `mvn -q test`
- Added integration tests that validate end-to-end component interplay:
  - [JoinServiceIntegrationTest.java](/home/moryash/ads/src/test/java/com/orionkv/integration/JoinServiceIntegrationTest.java)
  - [FailureRebalanceRoutingIntegrationTest.java](/home/moryash/ads/src/test/java/com/orionkv/integration/FailureRebalanceRoutingIntegrationTest.java)
  - [QuorumFlowIntegrationTest.java](/home/moryash/ads/src/test/java/com/orionkv/integration/QuorumFlowIntegrationTest.java)

These are code-level integration checks. The rest of this document is for **live scenario execution** and presentation evidence.

---

## 3) Preconditions and Tooling

## 3.1 Build
```bash
cd /home/moryash/ads
mvn -DskipTests clean package
```

## 3.2 Runtime settings (recommended for live demos)
- `node.virtual-node-count=64`
- `node.replication-factor=3`
- `node.write-quorum=2`
- `node.read-quorum=2`
- Faster visible transitions:
  - `node.suspect-timeout-ms=5000`
  - `node.dead-timeout-ms=12000`

## 3.3 Start nodes manually (no scripts)
Start each node in a separate terminal (or tmux pane). Example for node-1:
```bash
java -jar target/orionkv-0.0.1-SNAPSHOT.jar \
  --server.port=8081 \
  --node.node-id=node-1 \
  --node.address=127.0.0.1:9091 \
  --dataplane.storage.log-path=data/node-1.wal.log \
  --node.virtual-node-count=64 \
  --node.replication-factor=3 \
  --node.write-quorum=2 \
  --node.read-quorum=2 \
  --node.gossip-interval-ms=1000 \
  --node.self-heartbeat-interval-ms=500 \
  --node.failure-detection-interval-ms=1000 \
  --node.suspect-timeout-ms=5000 \
  --node.dead-timeout-ms=12000
```

For node-2..node-N add:
```bash
--node.seed-address=127.0.0.1:9091
```

---

## 4) Live Scenarios (Step-by-Step)

Use at least 6-10 nodes for classroom live demo.  
For final report, execute same steps on 30-50 nodes.

## Scenario A: Minimal Movement on Join

Purpose:
- show only a subset of key ownership shifts when a node joins

Steps:
1. Start cluster with nodes `node-1..node-6`.
2. Insert baseline key set (manually sample 20-50 keys is enough for live demo).
3. For each sample key, record `replicaNodeIds` from `CoordinationRpc/Get`.
4. Start `node-7` (same config, new ports, seed=node-1).
5. Wait for gossip + bootstrap completion.
6. Query same sample keys again and compare `replicaNodeIds`.

Command pattern:
```bash
grpcurl -plaintext \
  -d '{"requestId":"demo-get-1","key":"customer-1001"}' \
  -proto src/main/proto/coordination.proto \
  127.0.0.1:9091 orionkv.node.CoordinationRpc/Get
```

Evidence to capture:
- number of keys whose primary replica changed
- percentage moved = `moved_keys / sampled_keys * 100`
- before/after replica sets for representative keys

Acceptance:
- movement is bounded subset, not full reshuffle

---

## Scenario B: Single-Node Crash, Quorum Availability

Purpose:
- demonstrate availability with one failed node under `W=2, R=2`

Steps:
1. Keep steady PUT/GET traffic from client terminal.
2. Kill one node process (`node-k`) abruptly.
3. Continue PUT/GET traffic during SUSPECT->DEAD transition.
4. Observe `ack_count`, read response counts, and failures.

Expected:
- many requests still succeed (`>=2` acks/responses where routes allow)
- dead node eventually removed from replica routing

Evidence:
- sample of successful responses during failure window
- membership state transition timestamps from logs

---

## Scenario C: Two-Node Failure Threshold

Purpose:
- show expected degradation when quorum cannot be met for some keys/routes

Steps:
1. From healthy cluster, kill two nodes.
2. Continue GET/PUT requests.
3. Record response messages for quorum failures.

Expected:
- noticeable increase in `write quorum not met` and/or `read quorum not met`
- no silent corruption or false-success behavior

Evidence:
- failure rate before vs after second node kill

---

## Scenario D: Crash + Restart Durability (WAL)

Purpose:
- prove persisted data survives restart

Steps:
1. Write identifiable checkpoint keys (`k1..k20`) with known values/timestamps.
2. Kill target node(s).
3. Restart same node IDs with same WAL path.
4. Query checkpoint keys through coordinator.

Expected:
- recovered keys accessible after restart
- timestamps/values consistent with LWW rules

Evidence:
- before/after table of key, value, timestamp

---

## Scenario E: DEAD Rejoin Behavior

Purpose:
- validate dead-node rejoin and range refill behavior

Steps:
1. Force a node to be marked DEAD (wait for detector timeout).
2. Bring same node back up.
3. Observe whether node rejoins ALIVE and receives data for its new ranges.
4. Query keys expected in the node’s assigned ranges via coordinator and/or internal replica endpoints.

Expected:
- node reappears as ALIVE
- routing includes node where expected
- rebalance transfer occurs for gained ranges

Evidence:
- log lines for rejoin/bootstrap/reset
- sample keys now served from new replica sets including rejoined node

---

## Scenario F: Hot-Key Read/Write Stress (Live)

Purpose:
- show behavior under skewed access and conflict pressure

Steps:
1. Define hot key set (e.g., 20 keys).
2. Phase 1: mostly GETs on hot keys.
3. Phase 2: frequent PUTs on same hot keys from different coordinators.
4. Compare observed latency and LWW behavior.

Expected:
- read path remains available under skew
- winner version follows latest timestamp policy

Evidence:
- sampled p95 latency manually (or from timestamps in client logs)
- conflicting write timeline and final resolved value

---

## 5) Live Result Recording Templates

## 5.1 Summary Table (fill manually after each scenario)
| Scenario | Start Time | End Time | Nodes | N/W/R | Keys Sampled | Success % | Failure % | Key Finding |
|---|---|---|---:|---|---:|---:|---:|---|
| A Join Minimal Movement |  |  |  | 3/2/2 |  |  |  |  |
| B Single Failure |  |  |  | 3/2/2 |  |  |  |  |
| C Double Failure |  |  |  | 3/2/2 |  |  |  |  |
| D Crash Recovery |  |  |  | 3/2/2 |  |  |  |  |
| E Dead Rejoin |  |  |  | 3/2/2 |  |  |  |  |
| F Hot-Key Stress |  |  |  | 3/2/2 |  |  |  |  |

## 5.2 Scenario A Movement Table
| Key | Primary Before Join | Primary After Join | Moved (Y/N) |
|---|---|---|---|

## 5.3 Durability/Rejoin Verification Table
| Key | Expected Value | Expected TS | Actual Value | Actual TS | Match |
|---|---|---:|---|---:|---|

---

## 6) Integration Tests to Run Alongside Live Demo

Run:
```bash
mvn -q -Dtest=JoinServiceIntegrationTest,FailureRebalanceRoutingIntegrationTest,QuorumFlowIntegrationTest test
```

What they cover:
- join + bootstrap + dead-node reset path
- dead-node failure rebalance + routing exclusion
- quorum write/read with one dead replica and local storage integration

These tests are not replacements for live demos; they are reproducible support evidence.

---

## 7) Final Presentation Checklist

- Screenshots/log snippets for each scenario
- Filled summary table and per-scenario evidence tables
- One chart each for:
  - success/failure trend during failures
  - key movement in join scenario
  - recovery correctness after restart/rejoin
- Mention explicit limitations:
  - fully distributed deployment pending
  - full ring snapshot/epoch tasking still future work

