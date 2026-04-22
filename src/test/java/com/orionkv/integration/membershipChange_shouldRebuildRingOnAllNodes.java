package com.orionkv.integration;

import com.orionkv.common.rpc.ProtoMapper;
import com.orionkv.config.NodeProperties;
import com.orionkv.controlplane.bootstrap.rpc.ClusterRpcHandler;
import com.orionkv.controlplane.membership.model.MemberRecord;
import com.orionkv.controlplane.membership.model.MemberStatus;
import com.orionkv.controlplane.membership.rpc.GossipRpcHandler;
import com.orionkv.controlplane.membership.service.FailureDetectorService;
import com.orionkv.controlplane.membership.service.MembershipService;
import com.orionkv.controlplane.ring.service.HashRingService;
import com.orionkv.controlplane.ring.service.VirtualNodeService;
import com.orionkv.proto.JoinNodeRequest;
import com.orionkv.proto.MembershipState;
import com.google.protobuf.Empty;
import io.grpc.stub.StreamObserver;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class membershipChange_shouldRebuildRingOnAllNodes {

    @Test
    void membershipChange_shouldRebuildRingOnAllNodes() {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-04-20T18:40:00Z"), ZoneOffset.UTC);
        NodeProperties nodeProperties = new NodeProperties();
        nodeProperties.setNodeId("node-a");
        nodeProperties.setAddress("127.0.0.1:9091");
        nodeProperties.setVirtualNodeCount(8);
        nodeProperties.setReplicationFactor(3);
        nodeProperties.setSuspectTimeoutMs(10);
        nodeProperties.setDeadTimeoutMs(20);

        MembershipService membershipService = new MembershipService(fixedClock);
        membershipService.mergeRemoteMembership(List.of(
                member("node-a", "127.0.0.1:9091", MemberStatus.ALIVE, 1L, "2026-04-20T18:40:00Z"),
                member("node-b", "127.0.0.1:9092", MemberStatus.ALIVE, 1L, "2026-04-20T18:39:00Z")
        ));

        CountingHashRingService countingHashRingService = new CountingHashRingService(new VirtualNodeService(), nodeProperties);
        ClusterRpcHandler clusterRpcHandler = new ClusterRpcHandler(membershipService, countingHashRingService, nodeProperties);
        GossipRpcHandler gossipRpcHandler = new GossipRpcHandler(membershipService, countingHashRingService, nodeProperties);
        FailureDetectorService failureDetectorService = new FailureDetectorService(
                membershipService,
                countingHashRingService,
                nodeProperties,
                fixedClock
        );

        clusterRpcHandler.join(
                JoinNodeRequest.newBuilder().setNodeId("node-c").setAddress("127.0.0.1:9093").build(),
                new NoopObserver<>()
        );

        MembershipState gossipState = ProtoMapper.toProto(new com.orionkv.common.dto.GossipResponse(
                "node-z",
                List.of(member("node-d", "127.0.0.1:9094", MemberStatus.ALIVE, 1L, "2026-04-20T18:40:00Z")),
                2L
        ));
        gossipRpcHandler.gossip(
                com.orionkv.proto.GossipPayload.newBuilder()
                        .setSourceNodeId("node-z")
                        .addAllMembership(gossipState.getMembershipList())
                        .build(),
                new NoopObserver<>()
        );

        failureDetectorService.scanMembership();
        clusterRpcHandler.getMembership(Empty.getDefaultInstance(), new NoopObserver<>());

        assertThat(countingHashRingService.rebuildCount.get()).isGreaterThanOrEqualTo(3);
    }

    private static MemberRecord member(
            String nodeId,
            String address,
            MemberStatus status,
            long incarnation,
            String lastSeen
    ) {
        return new MemberRecord(nodeId, address, status, incarnation, Instant.parse(lastSeen));
    }

    private static final class NoopObserver<T> implements StreamObserver<T> {
        @Override
        public void onNext(T value) {
        }

        @Override
        public void onError(Throwable t) {
        }

        @Override
        public void onCompleted() {
        }
    }

    private static final class CountingHashRingService extends HashRingService {
        private final AtomicInteger rebuildCount = new AtomicInteger();

        private CountingHashRingService(VirtualNodeService virtualNodeService, NodeProperties nodeProperties) {
            super(virtualNodeService, nodeProperties);
        }

        @Override
        public synchronized void rebuildRing(Collection<MemberRecord> members) {
            rebuildCount.incrementAndGet();
            super.rebuildRing(members);
        }
    }
}

