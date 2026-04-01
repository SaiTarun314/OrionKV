package com.orionkv.bootstrap.service;

import com.orionkv.common.dto.GossipResponse;
import com.orionkv.common.dto.JoinRequest;
import com.orionkv.common.rpc.ControlPlaneClient;
import com.orionkv.config.NodeProperties;
import com.orionkv.membership.service.MembershipService;
import com.orionkv.ring.model.TokenRange;
import com.orionkv.ring.service.HashRingService;
import com.orionkv.ring.service.VirtualNodeService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.orionkv.bootstrap.model.BootstrapState.JOINED;
import static com.orionkv.bootstrap.model.BootstrapState.JOINING;
import static com.orionkv.bootstrap.model.BootstrapState.NEW;
import static com.orionkv.bootstrap.model.BootstrapState.REBALANCING;

@Service
public class JoinService {

    private final MembershipService membershipService;
    private final HashRingService hashRingService;
    private final VirtualNodeService virtualNodeService;
    private final RebalanceService rebalanceService;
    private final NodeProperties nodeProperties;
    private final ControlPlaneClient controlPlaneClient;
    private final AtomicReference<com.orionkv.bootstrap.model.BootstrapState> bootstrapState =
            new AtomicReference<>(NEW);

    public JoinService(
            MembershipService membershipService,
            HashRingService hashRingService,
            VirtualNodeService virtualNodeService,
            RebalanceService rebalanceService,
            NodeProperties nodeProperties,
            ControlPlaneClient controlPlaneClient
    ) {
        this.membershipService = membershipService;
        this.hashRingService = hashRingService;
        this.virtualNodeService = virtualNodeService;
        this.rebalanceService = rebalanceService;
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

        List<TokenRange> previousRanges = hashRingService.getOwnedTokenRanges(nodeProperties.getNodeId());
        virtualNodeService.generateTokens(nodeProperties.getNodeId(), nodeProperties.getVirtualNodeCount());
        hashRingService.rebuildRing(membershipService.getMembershipSnapshot());
        List<TokenRange> currentRanges = hashRingService.getOwnedTokenRanges(nodeProperties.getNodeId());

        List<TokenRange> newRanges = rebalanceService.detectNewRangesForNode(
                previousRanges,
                currentRanges,
                nodeProperties.getNodeId()
        );

        bootstrapState.set(newRanges.isEmpty() ? JOINED : REBALANCING);
        return newRanges;
    }

    public com.orionkv.bootstrap.model.BootstrapState getBootstrapState() {
        return bootstrapState.get();
    }
}
