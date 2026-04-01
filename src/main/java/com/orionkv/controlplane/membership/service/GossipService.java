package com.orionkv.controlplane.membership.service;

import com.orionkv.common.dto.GossipRequest;
import com.orionkv.common.rpc.ControlPlaneClient;
import com.orionkv.config.NodeProperties;
import com.orionkv.controlplane.membership.model.MemberRecord;
import com.orionkv.controlplane.membership.model.MemberStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class GossipService {

    private static final Logger log = LoggerFactory.getLogger(GossipService.class);

    private final MembershipService membershipService;
    private final NodeProperties nodeProperties;
    private final ControlPlaneClient controlPlaneClient;

    public GossipService(
            MembershipService membershipService,
            NodeProperties nodeProperties,
            ControlPlaneClient controlPlaneClient
    ) {
        this.membershipService = membershipService;
        this.nodeProperties = nodeProperties;
        this.controlPlaneClient = controlPlaneClient;
    }

    @Scheduled(
            fixedDelayString = "${node.gossip-interval-ms:5000}",
            initialDelayString = "${node.gossip-interval-ms:5000}"
    )
    public void gossipMembership() {
        List<MemberRecord> peers = membershipService.getMembershipSnapshot().stream()
                .filter(this::isEligiblePeer)
                .toList();

        if (peers.isEmpty()) {
            return;
        }

        MemberRecord peer = peers.get(ThreadLocalRandom.current().nextInt(peers.size()));
        GossipRequest request = new GossipRequest(
                nodeProperties.getNodeId(),
                List.copyOf(membershipService.getMembershipSnapshot())
        );

        try {
            controlPlaneClient.gossip(peer.address(), request);
        } catch (RuntimeException ex) {
            log.warn("Gossip exchange with peer {} at {} failed", peer.nodeId(), peer.address(), ex);
        }
    }

    private boolean isEligiblePeer(MemberRecord memberRecord) {
        if (memberRecord.address() == null || memberRecord.address().isBlank()) {
            return false;
        }
        if (memberRecord.status() == MemberStatus.DEAD) {
            return false;
        }
        return nodeProperties.getNodeId() == null || !nodeProperties.getNodeId().equals(memberRecord.nodeId());
    }
}
