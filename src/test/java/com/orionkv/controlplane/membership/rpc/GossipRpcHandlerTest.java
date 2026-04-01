package com.orionkv.controlplane.membership.rpc;

import com.orionkv.config.NodeProperties;
import com.orionkv.controlplane.membership.model.MemberRecord;
import com.orionkv.controlplane.membership.model.MemberStatus;
import com.orionkv.controlplane.membership.service.MembershipService;
import com.orionkv.proto.GossipPayload;
import com.orionkv.proto.MemberRecordProto;
import com.orionkv.proto.MemberStatusProto;
import com.orionkv.proto.MembershipState;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GossipRpcHandlerTest {

    @Test
    void shouldMergeIncomingMembershipAndReturnLocalState() {
        MembershipService membershipService = new MembershipService(
                Clock.fixed(Instant.parse("2026-03-29T20:00:00Z"), ZoneOffset.UTC)
        );
        NodeProperties nodeProperties = new NodeProperties();
        nodeProperties.setNodeId("node-self");

        GossipRpcHandler handler = new GossipRpcHandler(membershipService, nodeProperties);
        RecordingObserver observer = new RecordingObserver();

        GossipPayload request = GossipPayload.newBuilder()
                .setSourceNodeId("node-a")
                .addMembership(MemberRecordProto.newBuilder()
                        .setNodeId("node-a")
                        .setAddress("127.0.0.1:8081")
                        .setStatus(MemberStatusProto.ALIVE)
                        .setIncarnation(2)
                        .setLastSeen("2026-03-29T19:59:00Z")
                        .build())
                .build();

        handler.gossip(request, observer);

        assertThat(membershipService.getMember("node-a")).get()
                .extracting(MemberRecord::status)
                .isEqualTo(MemberStatus.ALIVE);
        assertThat(observer.values).hasSize(1);
        assertThat(observer.values.get(0).getResponderNodeId()).isEqualTo("node-self");
        assertThat(observer.values.get(0).getMembershipList()).hasSize(1);
    }

    private static final class RecordingObserver implements StreamObserver<MembershipState> {
        private final List<MembershipState> values = new ArrayList<>();

        @Override
        public void onNext(MembershipState value) {
            values.add(value);
        }

        @Override
        public void onError(Throwable t) {
            throw new AssertionError(t);
        }

        @Override
        public void onCompleted() {
        }
    }
}
