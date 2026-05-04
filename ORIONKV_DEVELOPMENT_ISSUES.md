OrionKV Development History and Current Status

1. Package and repository reorganization
- Refactored package naming and project structure to align with `com.orionkv`
- Removed earlier naming drift around `ads/controlplane/node`
- Synced tests and source paths after refactors

2. Spring Boot and gRPC integration
- Added gRPC services alongside Spring Boot in the same node process
- Exposed control-plane and coordination/replica RPCs together
- Fixed startup ordering and seed join retry behavior

3. Membership instability during startup
- Non-seed nodes were attempting to join before the seed gRPC server was ready
- Added retry logic and startup sequencing to reduce early join failures

4. False all-DEAD and recovery issues
- Membership state could get stuck because of heartbeat/incarnation handling
- Fixed self-heartbeat and recovery behavior so nodes can reassert `ALIVE`

5. Ring inconsistency across nodes
- Different nodes temporarily computed different replica sets after failures
- Identified dependence on membership convergence and local ring rebuild timing
- Added routing and ring-consistency validation through runtime scripts

6. Quorum path implementation
- Added coordination RPC layer for quorum reads and writes
- Added deterministic replica routing and timestamp-based merge semantics
- Verified coordinator consistency across multiple nodes

7. Tombstone correctness bug
- Local reads were hiding tombstones while replica reads exposed them
- Fixed read-path consistency so all read paths return versioned state
- Moved visibility decision to the coordinator instead of storage

8. Dockerization and multi-node deployment
- Added Docker image
- Added generated Docker Compose flow for many-node clusters
- Added Ubuntu host bootstrap and end-to-end Docker scripts

9. Cluster audit tooling
- Added scripts for:
  - cluster smoke testing
  - bulk loading
  - balance audit
  - reset/restart workflows
- These scripts made it possible to validate:
  - ring agreement
  - replica distribution
  - stale replicas
  - missing replicas

10. Failure rebalance bug
- Failure rebalance originally handled mainly primary ownership movement
- Patched failure rebalance to backfill new replica ranges after node death

11. Gossip healing bug
- Gossip originally targeted dead nodes
- A stricter patch then prevented isolated nodes from healing their membership view
- Final behavior now:
  - prefers ALIVE peers
  - falls back to other reachable non-self peers when needed for healing

12. Rejoin backfill completeness
- Returning nodes could previously rejoin membership before all expected replica data was restored
- Completed the post-rejoin repair/backfill behavior so rejoined nodes recover their expected replica state

Current validated state
- Gossip membership works
- Failure detection works
- Consistent hashing with virtual nodes works
- Deterministic replica routing works after convergence
- Quorum reads and writes work
- Join, leave, failure rebalance, and rejoin recovery work
- Docker-based multi-node deployment works
- Audit tooling works for checking balance and replica completeness

Current status
- There are no known open correctness issues at this time
- Previously observed development issues have been resolved
- The system is in a stable validated state based on the current test and Docker validation flows
