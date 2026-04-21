package com.orionkv.controlplane.bootstrap.service;

import com.orionkv.config.NodeProperties;
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
import static org.assertj.core.api.Assertions.assertThatCode;

class FailureRebalanceServiceTest {

    @Test
    void rebalancesOnlyAfterNodeIsMarkedDead() {
        MembershipService membershipService = new MembershipService(
                Clock.fixed(Instant.parse("2026-04-17T20:00:00Z"), ZoneOffset.UTC)
        );
        NodeProperties nodeProperties = new NodeProperties();
        nodeProperties.setNodeId("node-a");
        nodeProperties.setVirtualNodeCount(8);
        nodeProperties.setReplicationFactor(3);

        List<MemberRecord> members = List.of(
                member("node-a", MemberStatus.ALIVE, "127.0.0.1:9091"),
                member("node-b", MemberStatus.ALIVE, "127.0.0.1:9092"),
                member("node-c", MemberStatus.ALIVE, "127.0.0.1:9093"),
                member("node-d", MemberStatus.ALIVE, "127.0.0.1:9094")
        );
        membershipService.mergeRemoteMembership(members);

        HashRingService hashRingService = new HashRingService(new VirtualNodeService(), nodeProperties);
        hashRingService.rebuildRing(membershipService.getMembershipSnapshot());
        MemberRecord deadCandidate = findDeadCandidateThatAddsRanges(hashRingService, membershipService, nodeProperties);

        RecordingBootstrapReplicaClient bootstrapReplicaClient = new RecordingBootstrapReplicaClient();
        RecordingStorageService storageService = new RecordingStorageService();
        BootstrapTransferService bootstrapTransferService = new BootstrapTransferService(bootstrapReplicaClient, storageService);
        FailureRebalanceService failureRebalanceService = new FailureRebalanceService(
                membershipService,
                hashRingService,
                new RebalanceService(),
                bootstrapTransferService,
                nodeProperties
        );

        membershipService.markSuspect(deadCandidate.nodeId());
        failureRebalanceService.rebalanceDeadMembers();
        assertThat(bootstrapReplicaClient.requests).isEmpty();

        membershipService.markDead(deadCandidate.nodeId());
        failureRebalanceService.rebalanceDeadMembers();

        assertThat(bootstrapReplicaClient.requests).isNotEmpty();
        assertThat(storageService.appliedRecords).isNotEmpty();
    }

    @Test
    void rebalancesWhenNodeGainsReplicaRangeWithoutPrimaryOwnershipChange() {
        MembershipService membershipService = new MembershipService(
                Clock.fixed(Instant.parse("2026-04-17T20:00:00Z"), ZoneOffset.UTC)
        );
        NodeProperties nodeProperties = new NodeProperties();
        nodeProperties.setNodeId("node-a");
        nodeProperties.setVirtualNodeCount(16);
        nodeProperties.setReplicationFactor(3);

        List<MemberRecord> members = List.of(
                member("node-a", MemberStatus.ALIVE, "127.0.0.1:9091"),
                member("node-b", MemberStatus.ALIVE, "127.0.0.1:9092"),
                member("node-c", MemberStatus.ALIVE, "127.0.0.1:9093"),
                member("node-d", MemberStatus.ALIVE, "127.0.0.1:9094"),
                member("node-e", MemberStatus.ALIVE, "127.0.0.1:9095")
        );
        membershipService.mergeRemoteMembership(members);

        HashRingService hashRingService = new HashRingService(new VirtualNodeService(), nodeProperties);
        hashRingService.rebuildRing(membershipService.getMembershipSnapshot());
        MemberRecord deadCandidate =
                findDeadCandidateThatAddsReplicaRangesWithoutPrimaryMovement(hashRingService, membershipService, nodeProperties);

        RecordingBootstrapReplicaClient bootstrapReplicaClient = new RecordingBootstrapReplicaClient();
        RecordingStorageService storageService = new RecordingStorageService();
        BootstrapTransferService bootstrapTransferService = new BootstrapTransferService(bootstrapReplicaClient, storageService);
        FailureRebalanceService failureRebalanceService = new FailureRebalanceService(
                membershipService,
                hashRingService,
                new RebalanceService(),
                bootstrapTransferService,
                nodeProperties
        );

        membershipService.markDead(deadCandidate.nodeId());
        failureRebalanceService.rebalanceDeadMembers();

        assertThat(bootstrapReplicaClient.requests).isNotEmpty();
        assertThat(storageService.appliedRecords).isNotEmpty();
    }

    @Test
    void doesNotCrashWhenNoDonorAddressCanBeResolved() {
        MembershipService membershipService = new MembershipService(
                Clock.fixed(Instant.parse("2026-04-17T20:00:00Z"), ZoneOffset.UTC)
        );
        NodeProperties nodeProperties = new NodeProperties();
        nodeProperties.setNodeId("node-a");
        nodeProperties.setVirtualNodeCount(8);
        nodeProperties.setReplicationFactor(3);

        List<MemberRecord> members = List.of(
                member("node-a", MemberStatus.ALIVE, "127.0.0.1:9091"),
                member("node-b", MemberStatus.DEAD, "127.0.0.1:9092")
        );
        membershipService.mergeRemoteMembership(members);

        HashRingService hashRingService = new HashRingService(new VirtualNodeService(), nodeProperties);
        RecordingBootstrapReplicaClient bootstrapReplicaClient = new RecordingBootstrapReplicaClient();
        RecordingStorageService storageService = new RecordingStorageService();
        BootstrapTransferService bootstrapTransferService = new BootstrapTransferService(bootstrapReplicaClient, storageService);
        FailureRebalanceService failureRebalanceService = new FailureRebalanceService(
                membershipService,
                hashRingService,
                new RebalanceService(),
                bootstrapTransferService,
                nodeProperties
        );

        assertThatCode(failureRebalanceService::rebalanceDeadMembers).doesNotThrowAnyException();
        assertThat(bootstrapReplicaClient.requests).isEmpty();
        assertThat(storageService.appliedRecords).isEmpty();
    }

    private MemberRecord findDeadCandidateThatAddsRanges(
            HashRingService hashRingService,
            MembershipService membershipService,
            NodeProperties nodeProperties
    ) {
        RebalanceService rebalanceService = new RebalanceService();
        List<MemberRecord> snapshot = membershipService.getMembershipSnapshot().stream().toList();

        for (MemberRecord candidate : snapshot) {
            if (candidate.nodeId().equals(nodeProperties.getNodeId())) {
                continue;
            }

            List<MemberRecord> previousMembership = snapshot;
            List<MemberRecord> currentMembership = snapshot.stream()
                    .map(member -> member.nodeId().equals(candidate.nodeId())
                            ? new MemberRecord(
                                    member.nodeId(),
                                    member.address(),
                                    MemberStatus.DEAD,
                                    member.incarnation(),
                                    member.lastSeen()
                            )
                            : member)
                    .toList();

            hashRingService.rebuildRing(previousMembership);
            List<TokenRange> previousRanges = hashRingService.getAllPrimaryRanges();
            hashRingService.rebuildRing(currentMembership);
            List<TokenRange> currentRanges = hashRingService.getAllPrimaryRanges();

            List<com.orionkv.controlplane.bootstrap.model.PrimaryOwnershipMovement> plans =
                    rebalanceService.computePrimaryOwnershipDiff(previousRanges, currentRanges);
            if (!rebalanceService.movementsForTargetNode(plans, nodeProperties.getNodeId()).isEmpty()) {
                hashRingService.rebuildRing(snapshot);
                return candidate;
            }
        }

        throw new AssertionError("Expected at least one dead node to produce new replica ranges");
    }

    private MemberRecord findDeadCandidateThatAddsReplicaRangesWithoutPrimaryMovement(
            HashRingService hashRingService,
            MembershipService membershipService,
            NodeProperties nodeProperties
    ) {
        RebalanceService rebalanceService = new RebalanceService();
        List<MemberRecord> snapshot = membershipService.getMembershipSnapshot().stream().toList();

        for (MemberRecord candidate : snapshot) {
            if (candidate.nodeId().equals(nodeProperties.getNodeId())) {
                continue;
            }

            List<MemberRecord> previousMembership = snapshot;
            List<MemberRecord> currentMembership = snapshot.stream()
                    .map(member -> member.nodeId().equals(candidate.nodeId())
                            ? new MemberRecord(
                                    member.nodeId(),
                                    member.address(),
                                    MemberStatus.DEAD,
                                    member.incarnation(),
                                    member.lastSeen()
                            )
                            : member)
                    .toList();

            hashRingService.rebuildRing(previousMembership);
            List<TokenRange> previousPrimaryRanges = hashRingService.getAllPrimaryRanges();
            List<TokenRange> previousReplicaRanges = hashRingService.getReplicaTokenRanges(nodeProperties.getNodeId());

            hashRingService.rebuildRing(currentMembership);
            List<TokenRange> currentPrimaryRanges = hashRingService.getAllPrimaryRanges();
            List<TokenRange> currentReplicaRanges = hashRingService.getReplicaTokenRanges(nodeProperties.getNodeId());

            boolean hasNoPrimaryMovement = rebalanceService.movementsForTargetNode(
                    rebalanceService.computePrimaryOwnershipDiff(previousPrimaryRanges, currentPrimaryRanges),
                    nodeProperties.getNodeId()
            ).isEmpty();
            boolean hasReplicaMovement = !rebalanceService.detectNewRangesForNode(
                    previousReplicaRanges,
                    currentReplicaRanges,
                    nodeProperties.getNodeId()
            ).isEmpty();

            if (hasNoPrimaryMovement && hasReplicaMovement) {
                hashRingService.rebuildRing(snapshot);
                return candidate;
            }
        }

        throw new AssertionError("Expected at least one dead node to add replica ranges without primary ownership change");
    }

    private MemberRecord member(String nodeId, MemberStatus status, String address) {
        return new MemberRecord(
                nodeId,
                address,
                status,
                1L,
                Instant.parse("2026-04-17T20:00:00Z")
        );
    }

    private static final class RecordingBootstrapReplicaClient implements BootstrapReplicaClient {

        private final List<String> requests = new ArrayList<>();

        @Override
        public ReplicaStreamPage streamRange(String address, long startToken, long endToken, int batchSize, String cursor) {
            requests.add(address + ":" + startToken + ":" + endToken);
            return new ReplicaStreamPage(
                    List.of(new StoredValue("dead-rebalance-key", "recovered", 500L, false, endToken, "node-c")),
                    null,
                    true
            );
        }
    }

    private static final class RecordingStorageService implements StorageService {

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
        }
    }
}
