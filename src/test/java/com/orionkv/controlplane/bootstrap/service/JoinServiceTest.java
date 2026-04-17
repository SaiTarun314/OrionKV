package com.orionkv.controlplane.bootstrap.service;

import com.orionkv.controlplane.bootstrap.model.BootstrapState;
import com.orionkv.common.dto.GossipRequest;
import com.orionkv.common.dto.GossipResponse;
import com.orionkv.common.dto.JoinRequest;
import com.orionkv.common.rpc.ControlPlaneClient;
import com.orionkv.dataplane.model.StoredValue;
import com.orionkv.dataplane.service.ReplicaStreamPage;
import com.orionkv.dataplane.service.StorageService;
import com.orionkv.config.NodeProperties;
import com.orionkv.controlplane.membership.model.MemberRecord;
import com.orionkv.controlplane.membership.service.MembershipService;
import com.orionkv.controlplane.ring.model.TokenRange;
import com.orionkv.controlplane.ring.service.HashRingService;
import com.orionkv.controlplane.ring.service.VirtualNodeService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class JoinServiceTest {

    @Test
    void shouldJoinSeedNodeAndDetectNewRanges() {
        MembershipService membershipService = new MembershipService(
                Clock.fixed(Instant.parse("2026-03-29T20:00:00Z"), ZoneOffset.UTC)
        );
        NodeProperties nodeProperties = new NodeProperties();
        nodeProperties.setNodeId("node-self");
        nodeProperties.setAddress("127.0.0.1:8080");
        nodeProperties.setVirtualNodeCount(4);
        nodeProperties.setReplicationFactor(2);

        HashRingService hashRingService = new HashRingService(new VirtualNodeService(), nodeProperties);
        RebalanceService rebalanceService = new RebalanceService();
        StubClient client = new StubClient();
        StubStorageService storageService = new StubStorageService();
        RecordingBootstrapReplicaClient bootstrapReplicaClient = new RecordingBootstrapReplicaClient();
        BootstrapTransferService bootstrapTransferService = new BootstrapTransferService(
                bootstrapReplicaClient,
                storageService
        );

        JoinService joinService = new JoinService(
                membershipService,
                hashRingService,
                new VirtualNodeService(),
                rebalanceService,
                bootstrapTransferService,
                nodeProperties,
                client
        );

        List<TokenRange> newRanges = joinService.joinCluster("127.0.0.1:9090");

        assertThat(membershipService.getMembershipSnapshot())
                .extracting(MemberRecord::nodeId)
                .contains("seed-node", "node-self");
        assertThat(newRanges).isNotEmpty();
        assertThat(joinService.getBootstrapState()).isEqualTo(BootstrapState.JOINED);
        assertThat(client.seedAddress).isEqualTo("127.0.0.1:9090");
        assertThat(bootstrapReplicaClient.requestedRanges)
                .hasSize(newRanges.size())
                .allMatch(request -> request.startsWith("127.0.0.1:9090:"));
        assertThat(storageService.appliedRecords)
                .hasSize(newRanges.size())
                .extracting(StoredValue::key)
                .containsOnly("bootstrap-key");
    }

    private static final class StubClient implements ControlPlaneClient {

        private String seedAddress;

        @Override
        public GossipResponse gossip(String peerAddress, GossipRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GossipResponse join(String seedAddress, JoinRequest request) {
            this.seedAddress = seedAddress;
            return new GossipResponse(
                    "seed-node",
                    List.of(new MemberRecord(
                            "seed-node",
                            "127.0.0.1:9090",
                            com.orionkv.controlplane.membership.model.MemberStatus.ALIVE,
                            1,
                            Instant.parse("2026-03-29T19:59:00Z")
                    ))
            );
        }

        @Override
        public GossipResponse getMembership(String peerAddress) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class RecordingBootstrapReplicaClient implements BootstrapReplicaClient {

        private final List<String> requestedRanges = new ArrayList<>();

        @Override
        public ReplicaStreamPage streamRange(String address, long startToken, long endToken, int batchSize, String cursor) {
            requestedRanges.add(address + ":" + startToken + ":" + endToken);
            return new ReplicaStreamPage(
                    List.of(new StoredValue("bootstrap-key", "bootstrap-value", 200L, false, endToken, "seed-node")),
                    null,
                    true
            );
        }
    }

    private static final class StubStorageService implements StorageService {

        private final List<StoredValue> appliedRecords = new ArrayList<>();

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
        public com.orionkv.dataplane.service.ReplicaApplyResult applyReplicaWrite(
                com.orionkv.dataplane.model.ReplicaRecord replicaRecord
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.orionkv.dataplane.service.BatchApplyResult applyReplicaBatch(
                List<com.orionkv.dataplane.model.ReplicaRecord> records
        ) {
            records.forEach(record -> appliedRecords.add(new StoredValue(
                    record.key(),
                    record.value(),
                    record.timestamp(),
                    record.tombstone(),
                    record.token(),
                    record.sourceNodeId()
            )));
            return new com.orionkv.dataplane.service.BatchApplyResult(records.size(), records.size(), 0);
        }
    }
}
