package com.orionkv.controlplane.membership.service;

import com.orionkv.config.NodeProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class SelfHeartbeatService {

    private final MembershipService membershipService;
    private final NodeProperties nodeProperties;

    public SelfHeartbeatService(MembershipService membershipService, NodeProperties nodeProperties) {
        this.membershipService = membershipService;
        this.nodeProperties = nodeProperties;
    }

    @Scheduled(
            fixedDelayString = "${node.self-heartbeat-interval-ms:1000}",
            initialDelayString = "${node.self-heartbeat-interval-ms:1000}"
    )
    public void refreshLocalHeartbeat() {
        if (nodeProperties.getNodeId() == null || nodeProperties.getNodeId().isBlank()
                || nodeProperties.getAddress() == null || nodeProperties.getAddress().isBlank()) {
            return;
        }
        long nextIncarnation = membershipService.getMember(nodeProperties.getNodeId())
                .map(existing -> existing.incarnation() + 1)
                .orElse(0L);
        membershipService.updateHeartbeat(nodeProperties.getNodeId(), nodeProperties.getAddress(), nextIncarnation);
    }
}
