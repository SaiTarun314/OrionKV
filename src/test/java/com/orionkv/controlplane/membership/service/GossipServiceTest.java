package com.orionkv.controlplane.membership.service;

import com.orionkv.common.dto.GossipRequest;
import com.orionkv.common.dto.GossipResponse;
import com.orionkv.common.dto.JoinRequest;
import com.orionkv.common.rpc.ControlPlaneClient;
import com.orionkv.config.NodeProperties;
import com.orionkv.controlplane.membership.model.MemberRecord;
import com.orionkv.controlplane.membership.model.MemberStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class GossipServiceTest {

    @Test
    void shouldSendMembershipToRandomEligiblePeer() {
        MembershipService membershipService = new MembershipService(
                Clock.fixed(Instant.parse("2026-03-29T20:00:00Z"), ZoneOffset.UTC)
        );
        membershipService.mergeRemoteMembership(new MemberRecord(
                "node-a",
                "http://127.0.0.1:8081",
                MemberStatus.ALIVE,
                1,
                Instant.parse("2026-03-29T19:59:00Z")
        ));
        membershipService.mergeRemoteMembership(new MemberRecord(
                "node-self",
                "http://127.0.0.1:8080",
                MemberStatus.ALIVE,
                1,
                Instant.parse("2026-03-29T20:00:00Z")
        ));

        NodeProperties nodeProperties = new NodeProperties();
        nodeProperties.setNodeId("node-self");
        nodeProperties.setAddress("127.0.0.1:8080");

        RecordingClient client = new RecordingClient();

        GossipService gossipService = new GossipService(membershipService, nodeProperties, client);
        gossipService.gossipMembership();

        assertThat(client.peerAddress).isEqualTo("http://127.0.0.1:8081");
        assertThat(client.request.membership()).hasSize(2);
    }

    private static final class RecordingClient implements ControlPlaneClient {

        private String peerAddress;
        private GossipRequest request;

        @Override
        public GossipResponse gossip(String peerAddress, GossipRequest request) {
            this.peerAddress = peerAddress;
            this.request = request;
            return new GossipResponse("node-a", java.util.List.of());
        }

        @Override
        public GossipResponse join(String seedAddress, JoinRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GossipResponse getMembership(String peerAddress) {
            throw new UnsupportedOperationException();
        }
    }
}
