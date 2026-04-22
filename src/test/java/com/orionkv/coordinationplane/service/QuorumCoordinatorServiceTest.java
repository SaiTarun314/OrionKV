package com.orionkv.coordinationplane.service;

import com.orionkv.config.NodeProperties;
import com.orionkv.coordinationplane.model.ReplicaRoute;
import com.orionkv.coordinationplane.rpc.ReplicaDataClient;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QuorumCoordinatorServiceTest {

    @Test
    void localCoordinatedWriteUsesRoutePrimaryToken() {
        NodeProperties nodeProperties = new NodeProperties();
        nodeProperties.setNodeId("node-a");
        nodeProperties.setReplicationFactor(3);
        nodeProperties.setWriteQuorum(2);
        nodeProperties.setReadQuorum(2);

        RecordingStorageService storageService = new RecordingStorageService();
        StubReplicaRoutingService replicaRoutingService = new StubReplicaRoutingService(
                new ReplicaRoute("rebalance-key", 424242L, List.of("node-a", "node-b", "node-c"))
        );
        QuorumCoordinatorService quorumCoordinatorService = new QuorumCoordinatorService(
                nodeProperties,
                replicaRoutingService,
                new QuorumService(),
                new MembershipService(Clock.fixed(Instant.parse("2026-04-17T20:00:00Z"), ZoneOffset.UTC)),
                storageService,
                new StubReplicaDataClient()
        );

        quorumCoordinatorService.put("req-1", "rebalance-key", "value", 1000L);

        assertThat(storageService.appliedReplicaWrites).hasSize(1);
        assertThat(storageService.appliedReplicaWrites.get(0).token()).isEqualTo(424242L);
        assertThat(storageService.appliedReplicaWrites.get(0).sourceNodeId()).isEqualTo("node-a");
    }

    @Test
    void localTombstoneWinsVisibilityDecisionAtCoordinator() {
        NodeProperties nodeProperties = new NodeProperties();
        nodeProperties.setNodeId("node-a");
        nodeProperties.setReplicationFactor(1);
        nodeProperties.setWriteQuorum(1);
        nodeProperties.setReadQuorum(1);

        RecordingStorageService storageService = new RecordingStorageService();
        storageService.versionedValue = new StoredValue("user-1", null, 300L, true, 111L, "node-a");
        StubReplicaRoutingService replicaRoutingService = new StubReplicaRoutingService(
                new ReplicaRoute("user-1", 111L, List.of("node-a"))
        );

        QuorumCoordinatorService quorumCoordinatorService = new QuorumCoordinatorService(
                nodeProperties,
                replicaRoutingService,
                new QuorumService(),
                new MembershipService(Clock.fixed(Instant.parse("2026-04-17T20:00:00Z"), ZoneOffset.UTC)),
                storageService,
                new StubReplicaDataClient()
        );

        var response = quorumCoordinatorService.get("req-2", "user-1");

        assertThat(response.getFound()).isFalse();
        assertThat(response.getRequiredResponses()).isEqualTo(1);
        assertThat(response.getResponseCount()).isEqualTo(1);
        assertThat(response.getMessage()).isEqualTo("not found");
    }

    @Test
    void deleteUsesTombstoneWriteThroughQuorumPath() {
        NodeProperties nodeProperties = new NodeProperties();
        nodeProperties.setNodeId("node-a");
        nodeProperties.setReplicationFactor(3);
        nodeProperties.setWriteQuorum(1);
        nodeProperties.setReadQuorum(2);

        RecordingStorageService storageService = new RecordingStorageService();
        StubReplicaRoutingService replicaRoutingService = new StubReplicaRoutingService(
                new ReplicaRoute("deleted-key", 5150L, List.of("node-a", "node-b", "node-c"))
        );

        QuorumCoordinatorService quorumCoordinatorService = new QuorumCoordinatorService(
                nodeProperties,
                replicaRoutingService,
                new QuorumService(),
                new MembershipService(Clock.fixed(Instant.parse("2026-04-17T20:00:00Z"), ZoneOffset.UTC)),
                storageService,
                new StubReplicaDataClient()
        );

        var response = quorumCoordinatorService.delete("req-delete-1", "deleted-key", 7000L);

        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getAckCount()).isEqualTo(1);
        assertThat(response.getRequiredAcks()).isEqualTo(1);
        assertThat(response.getAcknowledgedNodeIdsList()).containsExactly("node-a");
        assertThat(response.getMessage()).contains("delete quorum satisfied");
        assertThat(storageService.appliedReplicaWrites).hasSize(1);
        ReplicaRecord localReplicaWrite = storageService.appliedReplicaWrites.get(0);
        assertThat(localReplicaWrite.key()).isEqualTo("deleted-key");
        assertThat(localReplicaWrite.value()).isNull();
        assertThat(localReplicaWrite.tombstone()).isTrue();
        assertThat(localReplicaWrite.timestamp()).isEqualTo(7000L);
        assertThat(localReplicaWrite.token()).isEqualTo(5150L);
    }

    @Test
    void retryWithoutTimestampReusesOriginallyAssignedTimestamp() throws Exception {
        NodeProperties nodeProperties = new NodeProperties();
        nodeProperties.setNodeId("node-a");
        nodeProperties.setReplicationFactor(3);
        nodeProperties.setWriteQuorum(1);
        nodeProperties.setReadQuorum(2);

        RecordingStorageService storageService = new RecordingStorageService();
        StubReplicaRoutingService replicaRoutingService = new StubReplicaRoutingService(
                new ReplicaRoute("retry-key", 4242L, List.of("node-a", "node-b", "node-c"))
        );

        QuorumCoordinatorService quorumCoordinatorService = new QuorumCoordinatorService(
                nodeProperties,
                replicaRoutingService,
                new QuorumService(),
                new MembershipService(Clock.fixed(Instant.parse("2026-04-17T20:00:00Z"), ZoneOffset.UTC)),
                storageService,
                new StubReplicaDataClient()
        );

        var first = quorumCoordinatorService.put("retry-1", "retry-key", "value", 0L);
        Thread.sleep(5L);
        var second = quorumCoordinatorService.put("retry-1", "retry-key", "value", 0L);

        assertThat(first.getTimestamp()).isPositive();
        assertThat(second.getTimestamp()).isEqualTo(first.getTimestamp());
        assertThat(storageService.appliedReplicaWrites).hasSize(2);
        assertThat(storageService.appliedReplicaWrites.get(0).timestamp()).isEqualTo(first.getTimestamp());
        assertThat(storageService.appliedReplicaWrites.get(1).timestamp()).isEqualTo(first.getTimestamp());
    }

    @Test
    void getTriggersAsyncReadRepairForStaleReplicaResponses() throws Exception {
        NodeProperties nodeProperties = new NodeProperties();
        nodeProperties.setNodeId("node-a");
        nodeProperties.setReplicationFactor(3);
        nodeProperties.setWriteQuorum(1);
        nodeProperties.setReadQuorum(3);

        RecordingStorageService storageService = new RecordingStorageService();
        storageService.versionedValue = new StoredValue("repair-key", "local-stale", 100L, false, 777L, "node-a");
        StubReplicaRoutingService replicaRoutingService = new StubReplicaRoutingService(
                new ReplicaRoute("repair-key", 777L, List.of("node-a", "node-b", "node-c"))
        );

        MembershipService membershipService = new MembershipService(
                Clock.fixed(Instant.parse("2026-04-17T20:00:00Z"), ZoneOffset.UTC)
        );
        membershipService.mergeRemoteMembership(List.of(
                member("node-a", "127.0.0.1:9091"),
                member("node-b", "127.0.0.1:9092"),
                member("node-c", "127.0.0.1:9093")
        ));

        CountDownLatch staleReplicaRepairLatch = new CountDownLatch(1);
        List<String> repairRequests = new CopyOnWriteArrayList<>();
        ReplicaDataClient replicaDataClient = new ReplicaDataClient() {
            @Override
            public boolean putReplica(String targetAddress, String requestId, ReplicaRecord record) {
                repairRequests.add(targetAddress + "|" + requestId + "|" + record.timestamp());
                if ("127.0.0.1:9093".equals(targetAddress) && record.timestamp() == 300L) {
                    staleReplicaRepairLatch.countDown();
                }
                return true;
            }

            @Override
            public com.orionkv.coordinationplane.model.ReplicaReadResult getReplica(
                    String targetAddress,
                    String requestId,
                    String key
            ) {
                if ("127.0.0.1:9092".equals(targetAddress)) {
                    return new com.orionkv.coordinationplane.model.ReplicaReadResult(
                            true, true, key, "fresh", 777L, 300L, false, "node-b"
                    );
                }
                if ("127.0.0.1:9093".equals(targetAddress)) {
                    return new com.orionkv.coordinationplane.model.ReplicaReadResult(
                            true, true, key, "stale-remote", 777L, 200L, false, "node-c"
                    );
                }
                return new com.orionkv.coordinationplane.model.ReplicaReadResult(false, false, key, null, -1L, -1L, false, null);
            }
        };

        QuorumCoordinatorService quorumCoordinatorService = new QuorumCoordinatorService(
                nodeProperties,
                replicaRoutingService,
                new QuorumService(),
                membershipService,
                storageService,
                replicaDataClient
        );

        var response = quorumCoordinatorService.get("req-read-repair", "repair-key");

        assertThat(response.getFound()).isTrue();
        assertThat(response.getValue()).isEqualTo("fresh");
        assertThat(staleReplicaRepairLatch.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(repairRequests).anyMatch(entry -> entry.startsWith("127.0.0.1:9093|req-read-repair-read-repair|300"));
    }

    @Test
    void getExcludesSuspectReplicaAndReportsActiveAgreement() {
        NodeProperties nodeProperties = new NodeProperties();
        nodeProperties.setNodeId("node-a");
        nodeProperties.setReplicationFactor(3);
        nodeProperties.setWriteQuorum(2);
        nodeProperties.setReadQuorum(2);

        RecordingStorageService storageService = new RecordingStorageService();
        storageService.versionedValue = new StoredValue("demo-key", "fresh", 300L, false, 777L, "node-a");
        StubReplicaRoutingService replicaRoutingService = new StubReplicaRoutingService(
                new ReplicaRoute("demo-key", 777L, List.of("node-a", "node-b", "node-c"))
        );

        MembershipService membershipService = new MembershipService(
                Clock.fixed(Instant.parse("2026-04-17T20:00:00Z"), ZoneOffset.UTC)
        );
        membershipService.mergeRemoteMembership(List.of(
                member("node-a", "127.0.0.1:9091"),
                new MemberRecord("node-b", "127.0.0.1:9092", MemberStatus.SUSPECT, 1L,
                        Instant.parse("2026-04-17T20:00:00Z")),
                member("node-c", "127.0.0.1:9093")
        ));

        ReplicaDataClient replicaDataClient = new ReplicaDataClient() {
            @Override
            public boolean putReplica(String targetAddress, String requestId, ReplicaRecord record) {
                return true;
            }

            @Override
            public com.orionkv.coordinationplane.model.ReplicaReadResult getReplica(
                    String targetAddress,
                    String requestId,
                    String key
            ) {
                if ("127.0.0.1:9093".equals(targetAddress)) {
                    return new com.orionkv.coordinationplane.model.ReplicaReadResult(
                            true, true, key, "fresh", 777L, 300L, false, "node-c"
                    );
                }
                return new com.orionkv.coordinationplane.model.ReplicaReadResult(false, false, key, null, -1L, -1L, false, null);
            }
        };

        QuorumCoordinatorService quorumCoordinatorService = new QuorumCoordinatorService(
                nodeProperties,
                replicaRoutingService,
                new QuorumService(),
                membershipService,
                storageService,
                replicaDataClient
        );

        var response = quorumCoordinatorService.get("req-suspect-read", "demo-key");

        assertThat(response.getFound()).isTrue();
        assertThat(response.getReplicaNodeIdsList()).containsExactly("node-a", "node-c");
        assertThat(response.getRespondedNodeIdsList()).containsExactly("node-a", "node-c");
        assertThat(response.getResponseCount()).isEqualTo(2);
        assertThat(response.getMessage()).contains("2/2 active replicas");
    }

    @Test
    void writeFailsWhenActiveReplicasAreFewerThanRequiredQuorum() {
        NodeProperties nodeProperties = new NodeProperties();
        nodeProperties.setNodeId("node-a");
        nodeProperties.setReplicationFactor(3);
        nodeProperties.setWriteQuorum(3);
        nodeProperties.setReadQuorum(2);

        RecordingStorageService storageService = new RecordingStorageService();
        StubReplicaRoutingService replicaRoutingService = new StubReplicaRoutingService(
                new ReplicaRoute("degraded-key", 900L, List.of("node-a", "node-b", "node-c"))
        );

        MembershipService membershipService = new MembershipService(
                Clock.fixed(Instant.parse("2026-04-17T20:00:00Z"), ZoneOffset.UTC)
        );
        membershipService.mergeRemoteMembership(List.of(
                member("node-a", "127.0.0.1:9091"),
                new MemberRecord("node-b", "127.0.0.1:9092", MemberStatus.SUSPECT, 1L,
                        Instant.parse("2026-04-17T20:00:00Z")),
                member("node-c", "127.0.0.1:9093")
        ));

        QuorumCoordinatorService quorumCoordinatorService = new QuorumCoordinatorService(
                nodeProperties,
                replicaRoutingService,
                new QuorumService(),
                membershipService,
                storageService,
                new StubReplicaDataClient()
        );

        var response = quorumCoordinatorService.put("req-suspect-write", "degraded-key", "value", 1000L);

        assertThat(response.getSuccess()).isFalse();
        assertThat(response.getAckCount()).isEqualTo(2);
        assertThat(response.getReplicaNodeIdsList()).containsExactly("node-a", "node-c");
        assertThat(response.getAcknowledgedNodeIdsList()).containsExactly("node-a", "node-c");
        assertThat(response.getMessage()).contains("2/2 active replicas").contains("3 required");
    }

    private static MemberRecord member(String nodeId, String address) {
        return new MemberRecord(
                nodeId,
                address,
                MemberStatus.ALIVE,
                1L,
                Instant.parse("2026-04-17T20:00:00Z")
        );
    }

    private static final class StubReplicaRoutingService extends ReplicaRoutingService {

        private final ReplicaRoute route;

        private StubReplicaRoutingService(ReplicaRoute route) {
            super(null);
            this.route = route;
        }

        @Override
        public ReplicaRoute routeForKey(String key) {
            return route;
        }
    }

    private static final class RecordingStorageService implements StorageService {

        private final List<ReplicaRecord> appliedReplicaWrites = new ArrayList<>();
        private StoredValue versionedValue;

        @Override
        public StoredValue put(String key, String value, long timestamp) {
            throw new UnsupportedOperationException();
        }

        @Override
        public StoredValue get(String key) {
            if (versionedValue == null || !versionedValue.key().equals(key)) {
                throw new com.orionkv.dataplane.exception.KeyNotFoundException(key);
            }
            return versionedValue;
        }

        @Override
        public Optional<StoredValue> getVersioned(String key) {
            return Optional.ofNullable(versionedValue).filter(value -> value.key().equals(key));
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
            appliedReplicaWrites.add(replicaRecord);
            return new ReplicaApplyResult(
                    new StoredValue(
                            replicaRecord.key(),
                            replicaRecord.value(),
                            replicaRecord.timestamp(),
                            replicaRecord.tombstone(),
                            replicaRecord.token(),
                            replicaRecord.sourceNodeId()
                    ),
                    true
            );
        }

        @Override
        public BatchApplyResult applyReplicaBatch(List<ReplicaRecord> replicaRecords) {
            return new BatchApplyResult(replicaRecords.size(), replicaRecords.size(), 0);
        }

        @Override
        public void resetLocalState() {
        }
    }

    private static final class StubReplicaDataClient implements ReplicaDataClient {

        @Override
        public boolean putReplica(String targetAddress, String requestId, ReplicaRecord record) {
            return true;
        }

        @Override
        public com.orionkv.coordinationplane.model.ReplicaReadResult getReplica(
                String targetAddress,
                String requestId,
                String key
        ) {
            return new com.orionkv.coordinationplane.model.ReplicaReadResult(false, false, key, null, -1L, -1L, false, null);
        }
    }
}
