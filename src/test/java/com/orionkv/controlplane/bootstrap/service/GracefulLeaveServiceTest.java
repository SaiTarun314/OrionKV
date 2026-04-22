package com.orionkv.controlplane.bootstrap.service;

import com.orionkv.config.NodeProperties;
import com.orionkv.controlplane.membership.model.MemberRecord;
import com.orionkv.controlplane.membership.model.MemberStatus;
import com.orionkv.controlplane.membership.service.MembershipService;
import com.orionkv.controlplane.ring.model.TokenRange;
import com.orionkv.controlplane.ring.service.HashRingService;
import com.orionkv.controlplane.ring.service.VirtualNodeService;
import com.orionkv.coordinationplane.model.ReplicaReadResult;
import com.orionkv.coordinationplane.rpc.ReplicaDataClient;
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
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GracefulLeaveServiceTest {

    @Test
    void leaveTransfersOwnedDataAndMarksNodeDead() {
        NodeProperties nodeProperties = new NodeProperties();
        nodeProperties.setNodeId("node-a");
        nodeProperties.setAddress("127.0.0.1:9091");
        nodeProperties.setVirtualNodeCount(8);
        nodeProperties.setReplicationFactor(2);

        MembershipService membershipService = new MembershipService(
                Clock.fixed(Instant.parse("2026-04-20T18:00:00Z"), ZoneOffset.UTC)
        );
        membershipService.mergeRemoteMembership(List.of(
                member("node-a", "127.0.0.1:9091", MemberStatus.ALIVE),
                member("node-b", "127.0.0.1:9092", MemberStatus.ALIVE)
        ));

        StubHashRingService hashRingService = new StubHashRingService(nodeProperties);
        hashRingService.ownedRanges = List.of(new TokenRange(10L, 20L, "node-a"));
        hashRingService.successorNodeId = "node-b";

        RecordingStorageService storageService = new RecordingStorageService(
                List.of(new StoredValue("alpha", "one", 111L, false, 15L, "node-a"))
        );
        RecordingReplicaDataClient replicaDataClient = new RecordingReplicaDataClient(true);

        GracefulLeaveService gracefulLeaveService = new GracefulLeaveService(
                membershipService,
                hashRingService,
                storageService,
                replicaDataClient,
                nodeProperties
        );

        gracefulLeaveService.leaveCluster();

        assertThat(membershipService.getMember("node-a")).get().extracting(MemberRecord::status).isEqualTo(MemberStatus.DEAD);
        assertThat(replicaDataClient.targets).containsExactly("127.0.0.1:9092");
        assertThat(replicaDataClient.records).hasSize(1);
        assertThat(replicaDataClient.records.get(0).key()).isEqualTo("alpha");
        assertThat(replicaDataClient.records.get(0).timestamp()).isEqualTo(111L);
    }

    @Test
    void leaveRollbackRestoresAliveWhenTransferFails() {
        NodeProperties nodeProperties = new NodeProperties();
        nodeProperties.setNodeId("node-a");
        nodeProperties.setAddress("127.0.0.1:9091");
        nodeProperties.setVirtualNodeCount(8);
        nodeProperties.setReplicationFactor(2);

        MembershipService membershipService = new MembershipService(
                Clock.fixed(Instant.parse("2026-04-20T18:00:00Z"), ZoneOffset.UTC)
        );
        membershipService.mergeRemoteMembership(List.of(
                member("node-a", "127.0.0.1:9091", MemberStatus.ALIVE),
                member("node-b", "127.0.0.1:9092", MemberStatus.ALIVE)
        ));

        StubHashRingService hashRingService = new StubHashRingService(nodeProperties);
        hashRingService.ownedRanges = List.of(new TokenRange(10L, 20L, "node-a"));
        hashRingService.successorNodeId = "node-b";

        RecordingStorageService storageService = new RecordingStorageService(
                List.of(new StoredValue("alpha", "one", 111L, false, 15L, "node-a"))
        );
        RecordingReplicaDataClient replicaDataClient = new RecordingReplicaDataClient(false);

        GracefulLeaveService gracefulLeaveService = new GracefulLeaveService(
                membershipService,
                hashRingService,
                storageService,
                replicaDataClient,
                nodeProperties
        );

        assertThatThrownBy(gracefulLeaveService::leaveCluster).isInstanceOf(IllegalStateException.class);
        assertThat(membershipService.getMember("node-a")).get().extracting(MemberRecord::status).isEqualTo(MemberStatus.ALIVE);
    }

    private static MemberRecord member(String nodeId, String address, MemberStatus status) {
        return new MemberRecord(nodeId, address, status, 1L, Instant.parse("2026-04-20T18:00:00Z"));
    }

    private static final class StubHashRingService extends HashRingService {
        private List<TokenRange> ownedRanges = List.of();
        private String successorNodeId = null;

        private StubHashRingService(NodeProperties nodeProperties) {
            super(new VirtualNodeService(), nodeProperties);
        }

        @Override
        public synchronized void rebuildRing(java.util.Collection<MemberRecord> members) {
            // no-op for deterministic test control
        }

        @Override
        public synchronized List<TokenRange> getOwnedTokenRanges(String nodeId) {
            return ownedRanges;
        }

        @Override
        public synchronized Optional<String> findOwnerForToken(long token) {
            return Optional.ofNullable(successorNodeId);
        }
    }

    private static final class RecordingReplicaDataClient implements ReplicaDataClient {
        private final boolean ack;
        private final List<String> targets = new ArrayList<>();
        private final List<ReplicaRecord> records = new ArrayList<>();

        private RecordingReplicaDataClient(boolean ack) {
            this.ack = ack;
        }

        @Override
        public boolean putReplica(String targetAddress, String requestId, ReplicaRecord record) {
            targets.add(targetAddress);
            records.add(record);
            return ack;
        }

        @Override
        public ReplicaReadResult getReplica(String targetAddress, String requestId, String key) {
            return new ReplicaReadResult(false, false, key, null, -1L, -1L, false, null);
        }
    }

    private static final class RecordingStorageService implements StorageService {
        private final List<StoredValue> entries;

        private RecordingStorageService(List<StoredValue> entries) {
            this.entries = entries;
        }

        @Override
        public StoredValue put(String key, String value, long timestamp) {
            throw new UnsupportedOperationException();
        }

        @Override
        public StoredValue get(String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<StoredValue> getVersioned(String key) {
            return Optional.empty();
        }

        @Override
        public void delete(String key, long timestamp) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<StoredValue> scanRange(long startToken, long endToken) {
            return entries;
        }

        @Override
        public List<StoredValue> scanActiveRange(long startToken, long endToken) {
            return entries;
        }

        @Override
        public ReplicaStreamPage scanRangePage(long startToken, long endToken, int batchSize, String cursor) {
            if (cursor != null) {
                return new ReplicaStreamPage(List.of(), null, true);
            }
            return new ReplicaStreamPage(entries, null, true);
        }

        @Override
        public ReplicaApplyResult applyReplicaWrite(ReplicaRecord replicaRecord) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BatchApplyResult applyReplicaBatch(List<ReplicaRecord> replicaRecords) {
            return new BatchApplyResult(replicaRecords.size(), replicaRecords.size(), 0);
        }

        @Override
        public void resetLocalState() {
        }
    }
}
