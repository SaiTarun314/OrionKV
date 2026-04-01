package com.orionkv.controlplane.membership.service;

import com.orionkv.config.NodeProperties;
import com.orionkv.controlplane.membership.model.MemberRecord;
import com.orionkv.controlplane.membership.model.MemberStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Service
public class FailureDetectorService {

    private final MembershipService membershipService;
    private final NodeProperties nodeProperties;
    private final Clock clock;

    @Autowired
    public FailureDetectorService(MembershipService membershipService, NodeProperties nodeProperties) {
        this(membershipService, nodeProperties, Clock.systemUTC());
    }

    public FailureDetectorService(MembershipService membershipService, NodeProperties nodeProperties, Clock clock) {
        this.membershipService = membershipService;
        this.nodeProperties = nodeProperties;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${node.failure-detection-interval-ms:2000}",
            initialDelayString = "${node.failure-detection-interval-ms:2000}"
    )
    public void scanMembership() {
        Instant now = Instant.now(clock);

        for (MemberRecord memberRecord : membershipService.getMembershipSnapshot()) {
            if (shouldSkip(memberRecord)) {
                continue;
            }

            long ageMs = Duration.between(memberRecord.lastSeen(), now).toMillis();
            if (ageMs >= nodeProperties.getDeadTimeoutMs()) {
                membershipService.markDead(memberRecord.nodeId());
                continue;
            }

            if (ageMs >= nodeProperties.getSuspectTimeoutMs() && memberRecord.status() == MemberStatus.ALIVE) {
                membershipService.markSuspect(memberRecord.nodeId());
            }
        }
    }

    private boolean shouldSkip(MemberRecord memberRecord) {
        if (nodeProperties.getNodeId() != null && nodeProperties.getNodeId().equals(memberRecord.nodeId())) {
            return true;
        }
        return memberRecord.status() == MemberStatus.DEAD;
    }
}
