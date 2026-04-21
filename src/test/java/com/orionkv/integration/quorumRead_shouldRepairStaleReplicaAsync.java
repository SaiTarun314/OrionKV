package com.orionkv.integration;

import com.orionkv.config.NodeProperties;
import com.orionkv.coordinationplane.model.ReplicaReadResult;
import com.orionkv.coordinationplane.model.ReplicaRoute;
import com.orionkv.coordinationplane.rpc.ReplicaDataClient;
import com.orionkv.coordinationplane.service.QuorumCoordinatorService;
import com.orionkv.coordinationplane.service.QuorumService;
import com.orionkv.coordinationplane.service.ReplicaRoutingService;
import com.orionkv.controlplane.membership.model.MemberRecord;
import com.orionkv.controlplane.membership.model.MemberStatus;
import com.orionkv.controlplane.membership.service.MembershipService;
import com.orionkv.dataplane.model.ReplicaRecord;
import com.orionkv.dataplane.model.StoredValue;
import com.orionkv.dataplane.service.BatchApplyResult;
import com.orionkv.dataplane.service.ReplicaApplyResult;
import com.orionkv.dataplane.service.ReplicaStreamPage;
import com.orionkv.dataplane.service.StorageService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class quorumRead_shouldRepairStaleReplicaAsync {

    @Test
    void quorumRead_shouldRepairStaleReplicaAsync() throws Exception {
        NodeProperties nodeProperties = new NodeProperties();
        nodeProperties.setNodeId("node-a");
        nodeProperties.setReplicationFactor(3);
        nodeProperties.setWriteQuorum(2);
        nodeProperties.setReadQuorum(3);

        MembershipService membershipService = new MembershipService(
                Clock.fixed(Instant.parse("2026-04-20T18:35:00Z"), ZoneOffset.UTC)
        );
        membershipService.mergeRemoteMembership(List.of(
                member("node-a", "127.0.0.1:9091"),
                member("node-b", "127.0.0.1:9092"),
                member("node-c", "127.0.0.1:9093")
        ));

        FixedStorage storage = new FixedStorage(new StoredValue("repair-key", "old-local", 100L, false, 404L, "node-a"));
        CountDownLatch staleRepairLatch = new CountDownLatch(1);

        ReplicaDataClient replicaDataClient = new ReplicaDataClient() {
            @Override
            public boolean putReplica(String targetAddress, String requestId, ReplicaRecord record) {
                if ("127.0.0.1:9093".equals(targetAddress)
                        && requestId.endsWith("-read-repair")
                        && record.timestamp() == 500L) {
                    staleRepairLatch.countDown();
                }
                return true;
            }

            @Override
            public ReplicaReadResult getReplica(String targetAddress, String requestId, String key) {
                if ("127.0.0.1:9092".equals(targetAddress)) {
                    return new ReplicaReadResult(true, true, key, "fresh-value", 404L, 500L, false, "node-b");
                }
                if ("127.0.0.1:9093".equals(targetAddress)) {
                    return new ReplicaReadResult(true, true, key, "stale-value", 404L, 200L, false, "node-c");
                }
                return new ReplicaReadResult(false, false, key, null, -1L, -1L, false, null);
            }
        };

        QuorumCoordinatorService coordinatorService = new QuorumCoordinatorService(
                nodeProperties,
                new FixedRouteService(new ReplicaRoute("repair-key", 404L, List.of("node-a", "node-b", "node-c"))),
                new QuorumService(),
                membershipService,
                storage,
                replicaDataClient
        );

        var response = coordinatorService.get("req-read-repair-1", "repair-key");

        assertThat(response.getFound()).isTrue();
        assertThat(response.getValue()).isEqualTo("fresh-value");
        assertThat(staleRepairLatch.await(1, TimeUnit.SECONDS)).isTrue();
    }

    private static MemberRecord member(String nodeId, String address) {
        return new MemberRecord(nodeId, address, MemberStatus.ALIVE, 1L, Instant.parse("2026-04-20T18:35:00Z"));
    }

    private static final class FixedRouteService extends ReplicaRoutingService {
        private final ReplicaRoute route;

        private FixedRouteService(ReplicaRoute route) {
            super(null);
            this.route = route;
        }

        @Override
        public ReplicaRoute routeForKey(String key) {
            return route;
        }
    }

    private static final class FixedStorage implements StorageService {
        private StoredValue value;

        private FixedStorage(StoredValue value) {
            this.value = value;
        }

        @Override
        public StoredValue put(String key, String value, long timestamp) {
            throw new UnsupportedOperationException();
        }

        @Override
        public StoredValue get(String key) {
            return value;
        }

        @Override
        public Optional<StoredValue> getVersioned(String key) {
            return Optional.ofNullable(value);
        }

        @Override
        public void delete(String key, long timestamp) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<StoredValue> scanRange(long startToken, long endToken) {
            return List.of();
        }

        @Override
        public List<StoredValue> scanActiveRange(long startToken, long endToken) {
            return List.of();
        }

        @Override
        public ReplicaStreamPage scanRangePage(long startToken, long endToken, int batchSize, String cursor) {
            return new ReplicaStreamPage(List.of(), null, true);
        }

        @Override
        public ReplicaApplyResult applyReplicaWrite(ReplicaRecord replicaRecord) {
            value = new StoredValue(
                    replicaRecord.key(),
                    replicaRecord.value(),
                    replicaRecord.timestamp(),
                    replicaRecord.tombstone(),
                    replicaRecord.token(),
                    replicaRecord.sourceNodeId()
            );
            return new ReplicaApplyResult(value, true);
        }

        @Override
        public BatchApplyResult applyReplicaBatch(List<ReplicaRecord> replicaRecords) {
            return new BatchApplyResult(replicaRecords.size(), replicaRecords.size(), 0);
        }

        @Override
        public void resetLocalState() {
            value = null;
        }
    }
}

