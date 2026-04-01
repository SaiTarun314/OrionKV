package com.orionkv.ring.service;

import com.orionkv.config.NodeProperties;
import com.orionkv.membership.model.MemberRecord;
import com.orionkv.membership.model.MemberStatus;
import com.orionkv.ring.model.ReplicaSet;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HashRingServiceTest {

    @Test
    void shouldRebuildRingUsingAliveNodesOnly() {
        NodeProperties nodeProperties = new NodeProperties();
        nodeProperties.setVirtualNodeCount(4);
        nodeProperties.setReplicationFactor(2);

        HashRingService hashRingService = new HashRingService(new VirtualNodeService(), nodeProperties);
        hashRingService.rebuildRing(List.of(
                new MemberRecord("node-a", "http://127.0.0.1:8081", MemberStatus.ALIVE, 1, Instant.parse("2026-03-29T20:00:00Z")),
                new MemberRecord("node-b", "http://127.0.0.1:8082", MemberStatus.SUSPECT, 1, Instant.parse("2026-03-29T20:00:00Z")),
                new MemberRecord("node-c", "http://127.0.0.1:8083", MemberStatus.ALIVE, 1, Instant.parse("2026-03-29T20:00:00Z"))
        ));

        assertThat(hashRingService.getTokenOwnerships()).hasSize(8);
        assertThat(hashRingService.getTokenOwnerships())
                .extracting(tokenOwnership -> tokenOwnership.ownerNodeId())
                .containsOnly("node-a", "node-c");
    }

    @Test
    void shouldFindUniqueReplicasForKey() {
        NodeProperties nodeProperties = new NodeProperties();
        nodeProperties.setVirtualNodeCount(8);
        nodeProperties.setReplicationFactor(3);

        HashRingService hashRingService = new HashRingService(new VirtualNodeService(), nodeProperties);
        hashRingService.rebuildRing(List.of(
                new MemberRecord("node-a", "http://127.0.0.1:8081", MemberStatus.ALIVE, 1, Instant.parse("2026-03-29T20:00:00Z")),
                new MemberRecord("node-b", "http://127.0.0.1:8082", MemberStatus.ALIVE, 1, Instant.parse("2026-03-29T20:00:00Z")),
                new MemberRecord("node-c", "http://127.0.0.1:8083", MemberStatus.ALIVE, 1, Instant.parse("2026-03-29T20:00:00Z"))
        ));

        ReplicaSet replicaSet = hashRingService.findReplicas("customer-123");

        assertThat(replicaSet.token()).isGreaterThanOrEqualTo(0L);
        assertThat(replicaSet.replicaNodeIds()).hasSize(3);
        assertThat(replicaSet.replicaNodeIds()).doesNotHaveDuplicates();
        assertThat(replicaSet.replicaNodeIds()).containsOnly("node-a", "node-b", "node-c");
    }

    @Test
    void shouldReturnAvailableNodesWhenReplicationFactorExceedsClusterSize() {
        NodeProperties nodeProperties = new NodeProperties();
        nodeProperties.setVirtualNodeCount(4);
        nodeProperties.setReplicationFactor(3);

        HashRingService hashRingService = new HashRingService(new VirtualNodeService(), nodeProperties);
        hashRingService.rebuildRing(List.of(
                new MemberRecord("node-a", "http://127.0.0.1:8081", MemberStatus.ALIVE, 1, Instant.parse("2026-03-29T20:00:00Z")),
                new MemberRecord("node-b", "http://127.0.0.1:8082", MemberStatus.ALIVE, 1, Instant.parse("2026-03-29T20:00:00Z"))
        ));

        ReplicaSet replicaSet = hashRingService.findReplicas("order-42");

        assertThat(replicaSet.replicaNodeIds()).hasSize(2);
        assertThat(replicaSet.replicaNodeIds()).containsOnly("node-a", "node-b");
    }
}
