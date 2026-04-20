package com.orionkv.integration;

import com.orionkv.config.NodeProperties;
import com.orionkv.controlplane.bootstrap.service.BootstrapReplicaClient;
import com.orionkv.controlplane.bootstrap.service.BootstrapTransferService;
import com.orionkv.controlplane.bootstrap.service.FailureRebalanceService;
import com.orionkv.controlplane.bootstrap.service.RebalanceService;
import com.orionkv.controlplane.membership.model.MemberRecord;
import com.orionkv.controlplane.membership.model.MemberStatus;
import com.orionkv.controlplane.membership.service.MembershipService;
import com.orionkv.controlplane.ring.model.TokenRange;
import com.orionkv.controlplane.ring.service.HashRingService;
import com.orionkv.controlplane.ring.service.VirtualNodeService;
import com.orionkv.coordinationplane.model.ReplicaRoute;
import com.orionkv.coordinationplane.service.ReplicaRoutingService;
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

class FailureRebalanceRoutingIntegrationTest {

    @Test
    void deadNodeIsReroutedAndRebalancedForNewReplicaRanges() {
        MembershipService membershipService = new MembershipService(
                Clock.fixed(Instant.parse("2026-04-18T15:30:00Z"), ZoneOffset.UTC)
        );

        NodeProperties nodeProperties = new NodeProperties();
        nodeProperties.setNodeId("node-a");
        nodeProperties.setAddress("127.0.0.1:9091");
        nodeProperties.setVirtualNodeCount(8);
        nodeProperties.setReplicationFactor(3);

        List<MemberRecord> members = List.of(
                member("node-a", "127.0.0.1:9091"),
                member("node-b", "127.0.0.1:9092"),
                member("node-c", "127.0.0.1:9093"),
                member("node-d", "127.0.0.1:9094")
        );
        membershipService.mergeRemoteMembership(members);

        HashRingService hashRingService = new HashRingService(new VirtualNodeService(), nodeProperties);
        hashRingService.rebuildRing(membershipService.getMembershipSnapshot());
        ReplicaRoutingService replicaRoutingService = new ReplicaRoutingService(hashRingService, membershipService);

        MemberRecord deadCandidate = findDeadCandidateThatAddsRanges(hashRingService, membershipService, nodeProperties);
        String keyThroughDeadCandidate = findKeyRoutedThrough(deadCandidate.nodeId(), replicaRoutingService);
        ReplicaRoute beforeFailure = replicaRoutingService.routeForKey(keyThroughDeadCandidate);
        assertThat(beforeFailure.replicaNodeIds()).contains(deadCandidate.nodeId());

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

        ReplicaRoute afterFailure = replicaRoutingService.routeForKey(keyThroughDeadCandidate);
        assertThat(afterFailure.replicaNodeIds()).doesNotContain(deadCandidate.nodeId());
        assertThat(bootstrapReplicaClient.requests).isNotEmpty();
        assertThat(storageService.appliedRecords).isNotEmpty();
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
            List<TokenRange> previousRanges = hashRingService.getReplicaTokenRanges(nodeProperties.getNodeId());
            hashRingService.rebuildRing(currentMembership);
            List<TokenRange> currentRanges = hashRingService.getReplicaTokenRanges(nodeProperties.getNodeId());

            if (!rebalanceService.detectNewRangesForNode(previousRanges, currentRanges, nodeProperties.getNodeId()).isEmpty()) {
                hashRingService.rebuildRing(snapshot);
                return candidate;
            }
        }

        throw new AssertionError("Expected at least one dead node to produce new replica ranges");
    }

    private String findKeyRoutedThrough(String nodeId, ReplicaRoutingService replicaRoutingService) {
        for (int index = 0; index < 20_000; index++) {
            String key = "fail-key-" + index;
            if (replicaRoutingService.routeForKey(key).replicaNodeIds().contains(nodeId)) {
                return key;
            }
        }
        throw new AssertionError("Could not find key routed through " + nodeId);
    }

    private static MemberRecord member(String nodeId, String address) {
        return new MemberRecord(
                nodeId,
                address,
                MemberStatus.ALIVE,
                1L,
                Instant.parse("2026-04-18T15:30:00Z")
        );
    }

    private static final class RecordingBootstrapReplicaClient implements BootstrapReplicaClient {

        private final List<String> requests = new ArrayList<>();

        @Override
        public ReplicaStreamPage streamRange(String address, long startToken, long endToken, int batchSize, String cursor) {
            requests.add(address + ":" + startToken + ":" + endToken);
            return new ReplicaStreamPage(
                    List.of(new StoredValue("recovered-key", "recovered-value", 700L, false, endToken, "node-c")),
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

