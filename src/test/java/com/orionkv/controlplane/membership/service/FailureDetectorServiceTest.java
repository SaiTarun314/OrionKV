package com.orionkv.controlplane.membership.service;

import com.orionkv.config.NodeProperties;
import com.orionkv.controlplane.membership.model.MemberRecord;
import com.orionkv.controlplane.membership.model.MemberStatus;
import com.orionkv.controlplane.ring.service.HashRingService;
import com.orionkv.controlplane.ring.service.VirtualNodeService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class FailureDetectorServiceTest {

    @Test
    void shouldMarkAliveNodeSuspectWhenItPassesSuspectTimeout() {
        MembershipService membershipService = new MembershipService(
                Clock.fixed(Instant.parse("2026-03-29T20:00:00Z"), ZoneOffset.UTC)
        );
        membershipService.mergeRemoteMembership(new MemberRecord(
                "node-a",
                "http://127.0.0.1:8081",
                MemberStatus.ALIVE,
                1,
                Instant.parse("2026-03-29T19:59:45Z")
        ));

        NodeProperties nodeProperties = new NodeProperties();
        nodeProperties.setNodeId("node-self");
        nodeProperties.setVirtualNodeCount(8);
        nodeProperties.setSuspectTimeoutMs(10_000);
        nodeProperties.setDeadTimeoutMs(30_000);
        HashRingService hashRingService = new HashRingService(new VirtualNodeService(), nodeProperties);

        FailureDetectorService failureDetectorService = new FailureDetectorService(
                membershipService,
                hashRingService,
                nodeProperties,
                Clock.fixed(Instant.parse("2026-03-29T20:00:00Z"), ZoneOffset.UTC)
        );

        failureDetectorService.scanMembership();

        assertThat(membershipService.getMember("node-a")).get()
                .extracting(MemberRecord::status)
                .isEqualTo(MemberStatus.SUSPECT);
    }

    @Test
    void shouldMarkNodeDeadWhenItPassesDeadTimeout() {
        MembershipService membershipService = new MembershipService(
                Clock.fixed(Instant.parse("2026-03-29T20:00:00Z"), ZoneOffset.UTC)
        );
        membershipService.mergeRemoteMembership(new MemberRecord(
                "node-a",
                "http://127.0.0.1:8081",
                MemberStatus.SUSPECT,
                1,
                Instant.parse("2026-03-29T19:59:20Z")
        ));

        NodeProperties nodeProperties = new NodeProperties();
        nodeProperties.setNodeId("node-self");
        nodeProperties.setVirtualNodeCount(8);
        nodeProperties.setSuspectTimeoutMs(10_000);
        nodeProperties.setDeadTimeoutMs(30_000);
        HashRingService hashRingService = new HashRingService(new VirtualNodeService(), nodeProperties);

        FailureDetectorService failureDetectorService = new FailureDetectorService(
                membershipService,
                hashRingService,
                nodeProperties,
                Clock.fixed(Instant.parse("2026-03-29T20:00:00Z"), ZoneOffset.UTC)
        );

        failureDetectorService.scanMembership();

        assertThat(membershipService.getMember("node-a")).get()
                .extracting(MemberRecord::status)
                .isEqualTo(MemberStatus.DEAD);
    }

    @Test
    void shouldNotChangeLocalNodeStatus() {
        MembershipService membershipService = new MembershipService(
                Clock.fixed(Instant.parse("2026-03-29T20:00:00Z"), ZoneOffset.UTC)
        );
        membershipService.mergeRemoteMembership(new MemberRecord(
                "node-self",
                "http://127.0.0.1:8080",
                MemberStatus.ALIVE,
                1,
                Instant.parse("2026-03-29T19:00:00Z")
        ));

        NodeProperties nodeProperties = new NodeProperties();
        nodeProperties.setNodeId("node-self");
        nodeProperties.setVirtualNodeCount(8);
        nodeProperties.setSuspectTimeoutMs(10_000);
        nodeProperties.setDeadTimeoutMs(30_000);
        HashRingService hashRingService = new HashRingService(new VirtualNodeService(), nodeProperties);

        FailureDetectorService failureDetectorService = new FailureDetectorService(
                membershipService,
                hashRingService,
                nodeProperties,
                Clock.fixed(Instant.parse("2026-03-29T20:00:00Z"), ZoneOffset.UTC)
        );

        failureDetectorService.scanMembership();

        assertThat(membershipService.getMember("node-self")).get()
                .extracting(MemberRecord::status)
                .isEqualTo(MemberStatus.ALIVE);
    }
}
