package com.orionkv.integration;

import com.orionkv.common.dto.GossipRequest;
import com.orionkv.common.dto.GossipResponse;
import com.orionkv.common.dto.JoinRequest;
import com.orionkv.common.rpc.ControlPlaneClient;
import com.orionkv.config.NodeProperties;
import com.orionkv.controlplane.bootstrap.model.BootstrapState;
import com.orionkv.controlplane.bootstrap.service.BootstrapReplicaClient;
import com.orionkv.controlplane.bootstrap.service.BootstrapTransferService;
import com.orionkv.controlplane.bootstrap.service.JoinService;
import com.orionkv.controlplane.bootstrap.service.RebalanceService;
import com.orionkv.controlplane.membership.model.MemberRecord;
import com.orionkv.controlplane.membership.model.MemberStatus;
import com.orionkv.controlplane.membership.service.MembershipService;
import com.orionkv.controlplane.ring.model.TokenRange;
import com.orionkv.controlplane.ring.service.HashRingService;
import com.orionkv.controlplane.ring.service.VirtualNodeService;
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

class JoinServiceIntegrationTest {

    @Test
    void joinResetsDeadNodeStateThenBootstrapsRanges() {
        MembershipService membershipService = new MembershipService(
                Clock.fixed(Instant.parse("2026-04-18T15:00:00Z"), ZoneOffset.UTC)
        );

        NodeProperties nodeProperties = new NodeProperties();
        nodeProperties.setNodeId("node-self");
        nodeProperties.setAddress("127.0.0.1:9100");
        nodeProperties.setVirtualNodeCount(8);
        nodeProperties.setReplicationFactor(3);

        HashRingService hashRingService = new HashRingService(new VirtualNodeService(), nodeProperties);
        RecordingStorageService storageService = new RecordingStorageService();
        RecordingBootstrapReplicaClient bootstrapReplicaClient = new RecordingBootstrapReplicaClient();
        BootstrapTransferService bootstrapTransferService = new BootstrapTransferService(
                bootstrapReplicaClient,
                storageService
        );

        ControlPlaneClient controlPlaneClient = new ReturningNodeControlPlaneClient();
        JoinService joinService = new JoinService(
                membershipService,
                hashRingService,
                new VirtualNodeService(),
                new RebalanceService(),
                bootstrapTransferService,
                storageService,
                nodeProperties,
                controlPlaneClient
        );

        List<TokenRange> ranges = joinService.joinCluster("127.0.0.1:9091");

        assertThat(storageService.resetCount).isEqualTo(1);
        assertThat(ranges).isNotEmpty();
        assertThat(bootstrapReplicaClient.requests).isNotEmpty();
        assertThat(storageService.appliedRecords).isNotEmpty();
        assertThat(joinService.getBootstrapState()).isEqualTo(BootstrapState.JOINED);
    }

    private static MemberRecord member(String nodeId, String address, MemberStatus status) {
        return new MemberRecord(
                nodeId,
                address,
                status,
                1L,
                Instant.parse("2026-04-18T15:00:00Z")
        );
    }

    private static final class ReturningNodeControlPlaneClient implements ControlPlaneClient {

        private int getMembershipCalls = 0;

        @Override
        public GossipResponse gossip(String peerAddress, GossipRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GossipResponse join(String seedAddress, JoinRequest request) {
            return new GossipResponse(
                    "seed-node",
                    List.of(
                            member("seed-node", "127.0.0.1:9091", MemberStatus.ALIVE),
                            member("node-b", "127.0.0.1:9092", MemberStatus.ALIVE),
                            member("node-c", "127.0.0.1:9093", MemberStatus.ALIVE)
                    ),
                    11L
            );
        }

        @Override
        public GossipResponse getMembership(String peerAddress) {
            getMembershipCalls++;
            if (getMembershipCalls == 1) {
                return new GossipResponse(
                        "seed-node",
                        List.of(
                                member("seed-node", "127.0.0.1:9091", MemberStatus.ALIVE),
                                member("node-self", "127.0.0.1:9100", MemberStatus.DEAD)
                        ),
                        10L
                );
            }
            return new GossipResponse(
                    "seed-node",
                    List.of(
                            member("seed-node", "127.0.0.1:9091", MemberStatus.ALIVE),
                            member("node-b", "127.0.0.1:9092", MemberStatus.ALIVE),
                            member("node-c", "127.0.0.1:9093", MemberStatus.ALIVE)
                    ),
                    11L
            );
        }
    }

    private static final class RecordingBootstrapReplicaClient implements BootstrapReplicaClient {

        private final List<String> requests = new ArrayList<>();

        @Override
        public ReplicaStreamPage streamRange(String address, long startToken, long endToken, int batchSize, String cursor) {
            requests.add(address + ":" + startToken + ":" + endToken);
            return new ReplicaStreamPage(
                    List.of(new StoredValue("bootstrap-key", "bootstrap-value", 300L, false, endToken, "seed-node")),
                    null,
                    true
            );
        }
    }

    private static final class RecordingStorageService implements StorageService {

        private int resetCount = 0;
        private final List<ReplicaRecord> appliedRecords = new ArrayList<>();

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
        public ReplicaApplyResult applyReplicaWrite(ReplicaRecord replicaRecord) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BatchApplyResult applyReplicaBatch(List<ReplicaRecord> replicaRecords) {
            appliedRecords.addAll(replicaRecords);
            return new BatchApplyResult(replicaRecords.size(), replicaRecords.size(), 0);
        }

        @Override
        public void resetLocalState() {
            resetCount++;
            appliedRecords.clear();
        }
    }
}

