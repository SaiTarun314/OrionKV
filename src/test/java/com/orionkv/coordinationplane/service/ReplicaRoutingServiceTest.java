package com.orionkv.coordinationplane.service;

import com.orionkv.coordinationplane.model.ReplicaRoute;
import com.orionkv.config.NodeProperties;
import com.orionkv.controlplane.membership.model.MemberRecord;
import com.orionkv.controlplane.membership.service.MembershipService;
import com.orionkv.controlplane.ring.service.HashRingService;
import com.orionkv.controlplane.ring.service.VirtualNodeService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReplicaRoutingServiceTest {

    @Test
    void routeForKeyExcludesDeadNodesAfterMembershipChanges() {
        MembershipService membershipService = new MembershipService(
                Clock.fixed(Instant.parse("2026-04-17T14:00:00Z"), ZoneOffset.UTC)
        );
        NodeProperties nodeProperties = new NodeProperties();
        nodeProperties.setReplicationFactor(3);
        nodeProperties.setVirtualNodeCount(8);

        membershipService.mergeRemoteMembership(List.of(
                member("node-a"),
                member("node-b"),
                member("node-c"),
                member("node-d")
        ));

        HashRingService hashRingService = new HashRingService(new VirtualNodeService(), nodeProperties);
        ReplicaRoutingService replicaRoutingService = new ReplicaRoutingService(hashRingService, membershipService);

        String key = findKeyRoutedThrough("node-b", replicaRoutingService);
        ReplicaRoute initialRoute = replicaRoutingService.routeForKey(key);
        assertThat(initialRoute.replicaNodeIds()).contains("node-b");

        membershipService.markDead("node-b");

        ReplicaRoute rerouted = replicaRoutingService.routeForKey(key);
        assertThat(rerouted.replicaNodeIds()).doesNotContain("node-b");
        assertThat(rerouted.replicaNodeIds()).containsOnly("node-a", "node-c", "node-d");
    }

    private String findKeyRoutedThrough(String nodeId, ReplicaRoutingService replicaRoutingService) {
        for (int index = 0; index < 10_000; index++) {
            String key = "route-key-" + index;
            if (replicaRoutingService.routeForKey(key).replicaNodeIds().contains(nodeId)) {
                return key;
            }
        }
        throw new AssertionError("Could not find key routed through " + nodeId);
    }

    private MemberRecord member(String nodeId) {
        return new MemberRecord(
                nodeId,
                "127.0.0.1:" + switch (nodeId) {
                    case "node-a" -> "9091";
                    case "node-b" -> "9092";
                    case "node-c" -> "9093";
                    default -> "9094";
                },
                com.orionkv.controlplane.membership.model.MemberStatus.ALIVE,
                1L,
                Instant.parse("2026-04-17T14:00:00Z")
        );
    }
}
