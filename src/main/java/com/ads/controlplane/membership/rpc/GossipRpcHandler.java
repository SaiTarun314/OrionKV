package com.ads.controlplane.membership.rpc;

import com.ads.controlplane.common.dto.GossipResponse;
import com.ads.controlplane.common.rpc.ProtoMapper;
import com.ads.controlplane.config.NodeProperties;
import com.ads.controlplane.membership.service.MembershipService;
import com.ads.controlplane.proto.GossipPayload;
import com.ads.controlplane.proto.GossipRpcGrpc;
import com.ads.controlplane.proto.MembershipState;
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
