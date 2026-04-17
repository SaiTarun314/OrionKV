package com.orionkv.controlplane.bootstrap.service;

import com.orionkv.common.dto.GossipResponse;
import com.orionkv.common.dto.JoinRequest;
import com.orionkv.common.rpc.ControlPlaneClient;
import com.orionkv.config.NodeProperties;
import com.orionkv.controlplane.membership.service.MembershipService;
import com.orionkv.controlplane.membership.model.MemberRecord;
import com.orionkv.controlplane.ring.model.TokenRange;
import com.orionkv.controlplane.ring.service.HashRingService;
import com.orionkv.controlplane.ring.service.VirtualNodeService;
import org.springframework.stereotype.Service;

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
            NodeProperties nodeProperties,
            ControlPlaneClient controlPlaneClient
    ) {
        this.membershipService = membershipService;
        this.hashRingService = hashRingService;
        this.virtualNodeService = virtualNodeService;
        this.rebalanceService = rebalanceService;
        this.bootstrapTransferService = bootstrapTransferService;
        this.nodeProperties = nodeProperties;
        this.controlPlaneClient = controlPlaneClient;
    }

    public List<TokenRange> joinCluster(String seedAddress) {
        bootstrapState.set(JOINING);

        GossipResponse response = controlPlaneClient.join(
                seedAddress,
                new JoinRequest(nodeProperties.getNodeId(), nodeProperties.getAddress())
        );

        if (response == null) {
            throw new IllegalStateException("Seed node returned no join response");
        }

        membershipService.mergeRemoteMembership(response.membership());
        membershipService.updateHeartbeat(nodeProperties.getNodeId(), nodeProperties.getAddress(), 0);

        List<MemberRecord> previousMembership = membershipService.getMembershipSnapshot().stream()
                .filter(member -> !nodeProperties.getNodeId().equals(member.nodeId()))
                .toList();

        hashRingService.rebuildRing(previousMembership);
        List<TokenRange> previousRanges = hashRingService.getOwnedTokenRanges(nodeProperties.getNodeId());

        virtualNodeService.generateTokens(nodeProperties.getNodeId(), nodeProperties.getVirtualNodeCount());
        hashRingService.rebuildRing(membershipService.getMembershipSnapshot());
        List<TokenRange> currentRanges = hashRingService.getOwnedTokenRanges(nodeProperties.getNodeId());

        List<TokenRange> newRanges = rebalanceService.detectNewRangesForNode(
                previousRanges,
                currentRanges,
                nodeProperties.getNodeId()
        );

        if (newRanges.isEmpty()) {
            bootstrapState.set(JOINED);
            return newRanges;
        }

        bootstrapState.set(REBALANCING);
        Map<TokenRange, String> donorNodeIds = donorNodeIdsForRanges(previousMembership, newRanges);
        hashRingService.rebuildRing(membershipService.getMembershipSnapshot());
        bootstrapTransferService.transferNewRanges(newRanges, donorNodeIds, nodeProperties.getNodeId());
        bootstrapState.set(JOINED);
        return newRanges;
    }

    public com.orionkv.controlplane.bootstrap.model.BootstrapState getBootstrapState() {
        return bootstrapState.get();
    }

    private Map<TokenRange, String> donorNodeIdsForRanges(
            List<MemberRecord> previousMembership,
            List<TokenRange> newRanges
    ) {
        hashRingService.rebuildRing(previousMembership);
        Map<String, String> previousAddressesByNodeId = previousMembership.stream()
                .collect(java.util.stream.Collectors.toMap(MemberRecord::nodeId, MemberRecord::address, (left, right) -> left));
        Map<TokenRange, String> donorNodeIds = newRanges.stream()
                .collect(java.util.stream.Collectors.toMap(
                        range -> range,
                        range -> hashRingService.findOwnerForToken(range.endInclusive())
                                .map(previousAddressesByNodeId::get)
                                .orElse(null)
                ));
        hashRingService.rebuildRing(membershipService.getMembershipSnapshot());
        return donorNodeIds;
    }
}
