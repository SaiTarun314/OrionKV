package com.orionkv.integration;

import com.orionkv.common.dto.GossipRequest;
import com.orionkv.common.dto.GossipResponse;
import com.orionkv.common.dto.JoinRequest;
import com.orionkv.common.rpc.ControlPlaneClient;
import com.orionkv.config.NodeProperties;
import com.orionkv.controlplane.bootstrap.model.PrimaryOwnershipMovement;
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

class nodeJoin_shouldGeneratePrimaryOwnershipMovementPlans {

    @Test
    void nodeJoin_shouldGeneratePrimaryOwnershipMovementPlans() {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-04-20T18:45:00Z"), ZoneOffset.UTC);
        NodeProperties nodeProperties = new NodeProperties();
        nodeProperties.setNodeId("node-self");
        nodeProperties.setAddress("127.0.0.1:9100");
        nodeProperties.setVirtualNodeCount(8);
        nodeProperties.setReplicationFactor(3);

        MembershipService membershipService = new MembershipService(fixedClock);
        RecordingStorageService storageService = new RecordingStorageService();
        RecordingBootstrapReplicaClient bootstrapReplicaClient = new RecordingBootstrapReplicaClient();
        BootstrapTransferService transferService = new BootstrapTransferService(bootstrapReplicaClient, storageService);
        RebalanceService rebalanceService = new RebalanceService();

        List<MemberRecord> seedMembership = List.of(
                member("seed-node", "127.0.0.1:9091"),
                member("node-b", "127.0.0.1:9092"),
                member("node-c", "127.0.0.1:9093")
        );

        List<TokenRange> expectedRanges = expectedJoinRanges(seedMembership, nodeProperties, rebalanceService);

        JoinService joinService = new JoinService(
                membershipService,
                new HashRingService(new VirtualNodeService(), nodeProperties),
                new VirtualNodeService(),
                rebalanceService,
                transferService,
                storageService,
                nodeProperties,
                new FixedControlPlaneClient(seedMembership)
        );

        List<TokenRange> actualRanges = joinService.joinCluster("127.0.0.1:9091");

        assertThat(actualRanges).containsExactlyInAnyOrderElementsOf(expectedRanges);
        assertThat(bootstrapReplicaClient.requestedRanges).containsExactlyInAnyOrderElementsOf(expectedRanges);
    }

    private static List<TokenRange> expectedJoinRanges(
            List<MemberRecord> seedMembership,
            NodeProperties nodeProperties,
            RebalanceService rebalanceService
    ) {
        List<MemberRecord> previousMembership = seedMembership;
        List<MemberRecord> currentMembership = new ArrayList<>(seedMembership);
        currentMembership.add(member(nodeProperties.getNodeId(), nodeProperties.getAddress()));

        HashRingService ring = new HashRingService(new VirtualNodeService(), nodeProperties);
        ring.rebuildRing(previousMembership);
        List<TokenRange> oldPrimaryRanges = ring.getAllPrimaryRanges();
        ring.rebuildRing(currentMembership);
        List<TokenRange> newPrimaryRanges = ring.getAllPrimaryRanges();

        List<PrimaryOwnershipMovement> plans = rebalanceService.computePrimaryOwnershipDiff(oldPrimaryRanges, newPrimaryRanges);
        return rebalanceService.movementsForTargetNode(plans, nodeProperties.getNodeId()).stream()
                .map(plan -> new TokenRange(plan.startToken(), plan.endToken(), nodeProperties.getNodeId()))
                .toList();
    }

    private static MemberRecord member(String nodeId, String address) {
        return new MemberRecord(nodeId, address, MemberStatus.ALIVE, 1L, Instant.parse("2026-04-20T18:45:00Z"));
    }

    private static final class FixedControlPlaneClient implements ControlPlaneClient {
        private final List<MemberRecord> membership;

        private FixedControlPlaneClient(List<MemberRecord> membership) {
            this.membership = membership;
        }

        @Override
        public GossipResponse gossip(String peerAddress, GossipRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GossipResponse join(String seedAddress, JoinRequest request) {
            return new GossipResponse("seed-node", membership, 10L);
        }

        @Override
        public GossipResponse getMembership(String peerAddress) {
            return new GossipResponse("seed-node", membership, 10L);
        }
    }

    private static final class RecordingBootstrapReplicaClient implements BootstrapReplicaClient {
        private final List<TokenRange> requestedRanges = new ArrayList<>();

        @Override
        public ReplicaStreamPage streamRange(String address, long startToken, long endToken, int batchSize, String cursor) {
            requestedRanges.add(new TokenRange(startToken, endToken, "node-self"));
            return new ReplicaStreamPage(
                    List.of(new StoredValue("join-key", "join-value", 500L, false, endToken, "seed-node")),
                    null,
                    true
            );
        }
    }

    private static final class RecordingStorageService implements StorageService {
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
}

