package com.orionkv.coordinationplane.service;

import com.orionkv.config.NodeProperties;
import com.orionkv.controlplane.membership.model.MemberStatus;
import com.orionkv.controlplane.membership.service.MembershipService;
import com.orionkv.controlplane.ring.service.HashRingService;
import com.orionkv.controlplane.ring.service.VirtualNodeService;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ReplicaRoutingServiceTest {

    @Test
    void routeForKeyExcludesNodeAfterItIsMarkedDead() {
        NodeProperties nodeProperties = new NodeProperties();
        nodeProperties.setReplicationFactor(3);
        nodeProperties.setVirtualNodeCount(8);

        MembershipService membershipService = new MembershipService();
        membershipService.mergeRemoteMembership(new com.orionkv.controlplane.membership.model.MemberRecord(
                "node-a", "127.0.0.1:9091", MemberStatus.ALIVE, 1, Instant.parse("2026-04-17T17:00:00Z")
        ));
        membershipService.mergeRemoteMembership(new com.orionkv.controlplane.membership.model.MemberRecord(
                "node-b", "127.0.0.1:9092", MemberStatus.ALIVE, 1, Instant.parse("2026-04-17T17:00:00Z")
        ));
        membershipService.mergeRemoteMembership(new com.orionkv.controlplane.membership.model.MemberRecord(
                "node-c", "127.0.0.1:9093", MemberStatus.ALIVE, 1, Instant.parse("2026-04-17T17:00:00Z")
        ));
        membershipService.mergeRemoteMembership(new com.orionkv.controlplane.membership.model.MemberRecord(
                "node-d", "127.0.0.1:9094", MemberStatus.ALIVE, 1, Instant.parse("2026-04-17T17:00:00Z")
        ));

        HashRingService hashRingService = new HashRingService(new VirtualNodeService(), nodeProperties);
        ReplicaRoutingService routingService = new ReplicaRoutingService(hashRingService, membershipService);

        boolean keyFound = false;
        for (int index = 0; index < 500; index++) {
            String key = "quorum-key-" + index;
            var routeBeforeFailure = routingService.routeForKey(key);
            if (!routeBeforeFailure.replicaNodeIds().contains("node-b")) {
                continue;
            }

            keyFound = true;
            membershipService.markDead("node-b");

            var routeAfterFailure = routingService.routeForKey(key);
            assertThat(routeAfterFailure.replicaNodeIds()).doesNotContain("node-b");
            assertThat(routeAfterFailure.replicaNodeIds()).hasSize(3);
            assertThat(routeAfterFailure.replicaNodeIds()).containsOnly("node-a", "node-c", "node-d");
            break;
        }

        assertThat(keyFound).isTrue();
    }
}
