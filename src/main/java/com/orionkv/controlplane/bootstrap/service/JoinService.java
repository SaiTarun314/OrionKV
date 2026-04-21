package com.orionkv.controlplane.bootstrap.service;

import com.orionkv.common.dto.GossipResponse;
import com.orionkv.common.dto.JoinRequest;
import com.orionkv.common.rpc.ControlPlaneClient;
import com.orionkv.config.NodeProperties;
import com.orionkv.controlplane.membership.model.MemberRecord;
import com.orionkv.controlplane.membership.model.MemberStatus;
import com.orionkv.controlplane.membership.service.MembershipService;
import com.orionkv.controlplane.ring.model.TokenRange;
import com.orionkv.controlplane.ring.service.HashRingService;
import com.orionkv.controlplane.ring.service.VirtualNodeService;
import com.orionkv.dataplane.service.StorageService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static com.orionkv.controlplane.bootstrap.model.BootstrapState.JOINED;
import static com.orionkv.controlplane.bootstrap.model.BootstrapState.JOINING;
import static com.orionkv.controlplane.bootstrap.model.BootstrapState.NEW;
import static com.orionkv.controlplane.bootstrap.model.BootstrapState.REBALANCING;

@Service
public class JoinService {

    private final MembershipService membershipService;
    private final HashRingService hashRingService;
    private final VirtualNodeService virtualNodeService;
    private final RebalanceService rebalanceService;
    private final BootstrapTransferService bootstrapTransferService;
    private final StorageService storageService;
    private final NodeProperties nodeProperties;
    private final ControlPlaneClient controlPlaneClient;
    private final AtomicReference<com.orionkv.controlplane.bootstrap.model.BootstrapState> bootstrapState =
            new AtomicReference<>(NEW);

    public JoinService(
            MembershipService membershipService,
            HashRingService hashRingService,
            VirtualNodeService virtualNodeService,
            RebalanceService rebalanceService,
            BootstrapTransferService bootstrapTransferService,
            StorageService storageService,
            NodeProperties nodeProperties,
            ControlPlaneClient controlPlaneClient
    ) {
        this.membershipService = membershipService;
        this.hashRingService = hashRingService;
        this.virtualNodeService = virtualNodeService;
        this.rebalanceService = rebalanceService;
        this.bootstrapTransferService = bootstrapTransferService;
        this.storageService = storageService;
        this.nodeProperties = nodeProperties;
        this.controlPlaneClient = controlPlaneClient;
    }

    public List<TokenRange> joinCluster(String seedAddress) {
        bootstrapState.set(JOINING);

        resetLocalStateIfReturningFromDead(seedAddress);

        GossipResponse response = controlPlaneClient.join(
                seedAddress,
                new JoinRequest(nodeProperties.getNodeId(), nodeProperties.getAddress())
        );

        if (response == null) {
            throw new IllegalStateException("Seed node returned no join response");
        }

        virtualNodeService.generateTokens(nodeProperties.getNodeId(), nodeProperties.getVirtualNodeCount());

        List<TokenRange> accumulatedRanges = new ArrayList<>();
        List<MemberRecord> currentSnapshot = snapshotWithSelf(response.membership());
        List<MemberRecord> previousSnapshot = currentSnapshot.stream()
                .filter(member -> !nodeProperties.getNodeId().equals(member.nodeId()))
                .toList();
        long observedTopologyVersion = response.topologyVersion();

        while (true) {
            membershipService.mergeRemoteMembership(currentSnapshot);
            membershipService.updateHeartbeat(nodeProperties.getNodeId(), nodeProperties.getAddress(), 0);
            hashRingService.rebuildRing(membershipService.getMembershipSnapshot());

            List<TokenRange> newRanges = transferNewRanges(previousSnapshot, currentSnapshot);
            newRanges.stream()
                    .filter(range -> !accumulatedRanges.contains(range))
                    .forEach(accumulatedRanges::add);

            GossipResponse latestResponse = controlPlaneClient.getMembership(seedAddress);
            if (latestResponse == null || latestResponse.topologyVersion() <= observedTopologyVersion) {
                bootstrapState.set(JOINED);
                return accumulatedRanges;
            }

            bootstrapState.set(REBALANCING);
            previousSnapshot = currentSnapshot;
            currentSnapshot = snapshotWithSelf(latestResponse.membership());
            observedTopologyVersion = latestResponse.topologyVersion();
        }
    }

    public com.orionkv.controlplane.bootstrap.model.BootstrapState getBootstrapState() {
        return bootstrapState.get();
    }

    private List<TokenRange> transferNewRanges(
            List<MemberRecord> previousMembership,
            List<MemberRecord> currentMembership
    ) {
        hashRingService.rebuildRing(previousMembership);
        List<TokenRange> previousReplicaRanges = hashRingService.getReplicaTokenRanges(nodeProperties.getNodeId());
        hashRingService.rebuildRing(currentMembership);
        List<TokenRange> currentReplicaRanges = hashRingService.getReplicaTokenRanges(nodeProperties.getNodeId());

        List<TokenRange> newRanges = rebalanceService.detectNewRangesForNode(
                previousReplicaRanges,
                currentReplicaRanges,
                nodeProperties.getNodeId()
        );

        if (newRanges.isEmpty()) {
            return newRanges;
        }

        bootstrapState.set(REBALANCING);
        Map<TokenRange, String> donorNodeIds = donorNodeIdsForRanges(previousMembership, currentMembership, newRanges);
        hashRingService.rebuildRing(currentMembership);
        bootstrapTransferService.transferNewRanges(newRanges, donorNodeIds, nodeProperties.getNodeId());
        return newRanges;
    }

    private Map<TokenRange, String> donorNodeIdsForRanges(
            List<MemberRecord> previousMembership,
            List<MemberRecord> currentMembership,
            List<TokenRange> newRanges
    ) {
        Map<String, MemberRecord> previousMembersByNodeId = previousMembership.stream()
                .collect(java.util.stream.Collectors.toMap(MemberRecord::nodeId, member -> member, (left, right) -> left));
        Map<String, MemberRecord> currentMembersByNodeId = currentMembership.stream()
                .collect(java.util.stream.Collectors.toMap(MemberRecord::nodeId, member -> member, (left, right) -> left));
        hashRingService.rebuildRing(previousMembership);
        Map<TokenRange, String> donorNodeIds = new LinkedHashMap<>();
        for (TokenRange range : newRanges) {
            String donorAddress = hashRingService.findReplicasForToken(range.endInclusive()).replicaNodeIds().stream()
                    .filter(candidateNodeId -> !candidateNodeId.equals(nodeProperties.getNodeId()))
                    .map(candidateNodeId ->
                            resolveAddress(candidateNodeId, previousMembersByNodeId, currentMembersByNodeId))
                    .filter(address -> address != null && !address.isBlank())
                    .findFirst()
                    .orElse(null);
            donorNodeIds.put(range, donorAddress);
        }
        hashRingService.rebuildRing(currentMembership);
        return donorNodeIds;
    }

    private String resolveAddress(
            String nodeId,
            Map<String, MemberRecord> previousMembersByNodeId,
            Map<String, MemberRecord> currentMembersByNodeId
    ) {
        if (nodeId == null || nodeId.equals(nodeProperties.getNodeId())) {
            return null;
        }

        MemberRecord current = currentMembersByNodeId.get(nodeId);
        if (current != null && current.status() == MemberStatus.ALIVE
                && current.address() != null && !current.address().isBlank()) {
            return current.address();
        }

        MemberRecord previous = previousMembersByNodeId.get(nodeId);
        if (previous != null && previous.status() == MemberStatus.ALIVE
                && previous.address() != null && !previous.address().isBlank()) {
            return previous.address();
        }

        return null;
    }

    private List<MemberRecord> snapshotWithSelf(List<MemberRecord> membership) {
        List<MemberRecord> snapshot = new ArrayList<>();
        if (membership != null) {
            snapshot.addAll(membership.stream()
                    .filter(member -> !nodeProperties.getNodeId().equals(member.nodeId()))
                    .toList());
        }
        snapshot.add(new MemberRecord(
                nodeProperties.getNodeId(),
                nodeProperties.getAddress(),
                MemberStatus.ALIVE,
                0L,
                java.time.Instant.now()
        ));
        return snapshot;
    }

    private void resetLocalStateIfReturningFromDead(String seedAddress) {
        GossipResponse currentClusterState = controlPlaneClient.getMembership(seedAddress);
        if (currentClusterState == null || currentClusterState.membership() == null) {
            return;
        }

        boolean returningFromDead = currentClusterState.membership().stream()
                .anyMatch(member -> member.nodeId().equals(nodeProperties.getNodeId()) && member.status() == MemberStatus.DEAD);

        if (returningFromDead) {
            storageService.resetLocalState();
        }
    }
}
