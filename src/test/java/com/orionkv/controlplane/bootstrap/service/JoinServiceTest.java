package com.orionkv.controlplane.bootstrap.service;

import com.orionkv.controlplane.bootstrap.model.BootstrapState;
import com.orionkv.common.dto.GossipRequest;
import com.orionkv.common.dto.GossipResponse;
import com.orionkv.common.dto.JoinRequest;
import com.orionkv.common.rpc.ControlPlaneClient;
import com.orionkv.config.NodeProperties;
import com.orionkv.controlplane.membership.model.MemberRecord;
import com.orionkv.controlplane.membership.service.MembershipService;
import com.orionkv.controlplane.ring.model.TokenRange;
import com.orionkv.controlplane.ring.service.HashRingService;
import com.orionkv.controlplane.ring.service.VirtualNodeService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JoinServiceTest {

    @Test
    void shouldJoinSeedNodeAndDetectNewRanges() {
        MembershipService membershipService = new MembershipService(
                Clock.fixed(Instant.parse("2026-03-29T20:00:00Z"), ZoneOffset.UTC)
        );
        NodeProperties nodeProperties = new NodeProperties();
        nodeProperties.setNodeId("node-self");
        nodeProperties.setAddress("127.0.0.1:8080");
        nodeProperties.setVirtualNodeCount(4);
        nodeProperties.setReplicationFactor(2);

        HashRingService hashRingService = new HashRingService(new VirtualNodeService(), nodeProperties);
        RebalanceService rebalanceService = new RebalanceService();
        StubClient client = new StubClient();

        JoinService joinService = new JoinService(
                membershipService,
                hashRingService,
                new VirtualNodeService(),
                rebalanceService,
                nodeProperties,
                client
        );

        List<TokenRange> newRanges = joinService.joinCluster("127.0.0.1:9090");

        assertThat(membershipService.getMembershipSnapshot())
                .extracting(MemberRecord::nodeId)
                .contains("seed-node", "node-self");
        assertThat(newRanges).isNotEmpty();
        assertThat(joinService.getBootstrapState()).isEqualTo(BootstrapState.REBALANCING);
        assertThat(client.seedAddress).isEqualTo("127.0.0.1:9090");
    }

    private static final class StubClient implements ControlPlaneClient {

        private String seedAddress;

        @Override
        public GossipResponse gossip(String peerAddress, GossipRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GossipResponse join(String seedAddress, JoinRequest request) {
            this.seedAddress = seedAddress;
            return new GossipResponse(
                    "seed-node",
                    List.of(new MemberRecord(
                            "seed-node",
                            "127.0.0.1:9090",
                            com.orionkv.controlplane.membership.model.MemberStatus.ALIVE,
                            1,
                            Instant.parse("2026-03-29T19:59:00Z")
                    ))
            );
        }

        @Override
        public GossipResponse getMembership(String peerAddress) {
            throw new UnsupportedOperationException();
        }
    }
}
