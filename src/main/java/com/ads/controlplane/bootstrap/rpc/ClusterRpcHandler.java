package com.ads.controlplane.bootstrap.rpc;

import com.ads.controlplane.common.dto.GossipResponse;
import com.ads.controlplane.common.dto.JoinRequest;
import com.ads.controlplane.common.rpc.ProtoMapper;
import com.ads.controlplane.config.NodeProperties;
import com.ads.controlplane.membership.service.MembershipService;
import com.ads.controlplane.proto.ClusterRpcGrpc;
import com.ads.controlplane.proto.JoinNodeRequest;
import com.ads.controlplane.proto.MembershipState;
import com.ads.controlplane.ring.service.HashRingService;
import com.google.protobuf.Empty;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;

@Component
public class ClusterRpcHandler extends ClusterRpcGrpc.ClusterRpcImplBase {

    private final MembershipService membershipService;
    private final HashRingService hashRingService;
    private final NodeProperties nodeProperties;

    public ClusterRpcHandler(
            MembershipService membershipService,
            HashRingService hashRingService,
            NodeProperties nodeProperties
    ) {
        this.membershipService = membershipService;
        this.hashRingService = hashRingService;
        this.nodeProperties = nodeProperties;
    }

    @Override
    public void join(JoinNodeRequest request, StreamObserver<MembershipState> responseObserver) {
        JoinRequest joinRequest = ProtoMapper.fromProto(request);
        membershipService.updateHeartbeat(joinRequest.nodeId(), joinRequest.address(), 0);
        hashRingService.rebuildRing(membershipService.getMembershipSnapshot());
        respond(responseObserver);
    }

    @Override
    public void getMembership(Empty request, StreamObserver<MembershipState> responseObserver) {
        respond(responseObserver);
    }

    private void respond(StreamObserver<MembershipState> responseObserver) {
        responseObserver.onNext(ProtoMapper.toProto(new GossipResponse(
                nodeProperties.getNodeId(),
                membershipService.getMembershipSnapshot().stream().toList()
        )));
        responseObserver.onCompleted();
    }
}
