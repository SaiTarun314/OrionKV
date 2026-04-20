package com.orionkv.controlplane.bootstrap.service;

import com.orionkv.common.dto.GossipResponse;
import com.orionkv.common.dto.JoinRequest;
import com.orionkv.common.rpc.ControlPlaneClient;
import com.orionkv.config.NodeProperties;
import com.orionkv.dataplane.service.StorageService;
import com.orionkv.controlplane.membership.service.MembershipService;
import com.orionkv.controlplane.membership.model.MemberRecord;
import com.orionkv.controlplane.membership.model.MemberStatus;
import com.orionkv.controlplane.ring.model.TokenRange;
import com.orionkv.controlplane.ring.service.HashRingService;
import com.orionkv.controlplane.ring.service.VirtualNodeService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
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
        List<TokenRange> previousRanges = hashRingService.getReplicaTokenRanges(nodeProperties.getNodeId());
        hashRingService.rebuildRing(currentMembership);
        List<TokenRange> currentRanges = hashRingService.getReplicaTokenRanges(nodeProperties.getNodeId());

        List<TokenRange> newRanges = rebalanceService.detectNewRangesForNode(
                previousRanges,
                currentRanges,
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
        Map<String, MemberRecord> currentMembersByNodeId = currentMembership.stream()
                .collect(java.util.stream.Collectors.toMap(MemberRecord::nodeId, member -> member, (left, right) -> left));
        Map<TokenRange, String> donorNodeIds = newRanges.stream()
                .collect(java.util.stream.Collectors.toMap(
                        range -> range,
                        range -> {
                            hashRingService.rebuildRing(previousMembership);
                            return hashRingService.findReplicasForToken(range.endInclusive()).replicaNodeIds().stream()
                                    .filter(candidateNodeId -> !candidateNodeId.equals(nodeProperties.getNodeId()))
                                    .map(currentMembersByNodeId::get)
                                    .filter(candidate -> candidate != null && candidate.status() == MemberStatus.ALIVE)
                                    .map(MemberRecord::address)
                                    .findFirst()
                                    .orElse(null);
                        }
                ));
        hashRingService.rebuildRing(currentMembership);
        return donorNodeIds;
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
