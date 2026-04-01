package com.orionkv.controlplane.membership.rpc;

import com.orionkv.common.dto.GossipResponse;
import com.orionkv.common.rpc.ProtoMapper;
import com.orionkv.config.NodeProperties;
import com.orionkv.controlplane.membership.service.MembershipService;
import com.orionkv.proto.GossipPayload;
import com.orionkv.proto.GossipRpcGrpc;
import com.orionkv.proto.MembershipState;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;

@Component
public class GossipRpcHandler extends GossipRpcGrpc.GossipRpcImplBase {

    private final MembershipService membershipService;
    private final NodeProperties nodeProperties;

    public GossipRpcHandler(MembershipService membershipService, NodeProperties nodeProperties) {
        this.membershipService = membershipService;
        this.nodeProperties = nodeProperties;
    }

    @Override
    public void gossip(GossipPayload request, StreamObserver<MembershipState> responseObserver) {
        membershipService.mergeRemoteMembership(ProtoMapper.fromProto(request).membership());
        responseObserver.onNext(ProtoMapper.toProto(new GossipResponse(
                nodeProperties.getNodeId(),
                membershipService.getMembershipSnapshot().stream().toList()
        )));
        responseObserver.onCompleted();
    }
}
