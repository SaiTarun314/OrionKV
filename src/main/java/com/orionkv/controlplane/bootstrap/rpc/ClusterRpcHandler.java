package com.orionkv.controlplane.bootstrap.rpc;

import com.orionkv.common.dto.GossipResponse;
import com.orionkv.common.dto.JoinRequest;
import com.orionkv.common.rpc.ProtoMapper;
import com.orionkv.config.NodeProperties;
import com.orionkv.controlplane.membership.service.MembershipService;
import com.orionkv.proto.ClusterRpcGrpc;
import com.orionkv.proto.JoinNodeRequest;
import com.orionkv.proto.MembershipState;
import com.orionkv.controlplane.ring.service.HashRingService;
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
