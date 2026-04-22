package com.orionkv.controlplane.membership.rpc;

import com.orionkv.common.dto.GossipRequest;
import com.orionkv.common.dto.GossipResponse;
import com.orionkv.common.rpc.ProtoMapper;
import com.orionkv.config.NodeProperties;
import com.orionkv.controlplane.membership.model.MemberRecord;
import com.orionkv.controlplane.membership.service.MembershipService;
import com.orionkv.controlplane.ring.service.HashRingService;
import com.orionkv.proto.GossipPayload;
import com.orionkv.proto.GossipRpcGrpc;
import com.orionkv.proto.MembershipState;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;

@Component
public class GossipRpcHandler extends GossipRpcGrpc.GossipRpcImplBase {

    private final MembershipService membershipService;
    private final HashRingService hashRingService;
    private final NodeProperties nodeProperties;

    public GossipRpcHandler(
            MembershipService membershipService,
            HashRingService hashRingService,
            NodeProperties nodeProperties
    ) {
        this.membershipService = membershipService;
        this.hashRingService = hashRingService;
        this.nodeProperties = nodeProperties;
    }

    @Override
    public void gossip(GossipPayload request, StreamObserver<MembershipState> responseObserver) {
        GossipRequest gossipRequest = ProtoMapper.fromProto(request);
        membershipService.mergeRemoteMembership(
                gossipRequest.membership().stream()
                        .filter(record -> nodeProperties.getNodeId() == null
                                || !nodeProperties.getNodeId().equals(record.nodeId()))
                        .toList()
        );
        refreshSourceHeartbeat(gossipRequest);
        hashRingService.rebuildRing(membershipService.getMembershipSnapshot());
        responseObserver.onNext(ProtoMapper.toProto(new GossipResponse(
                nodeProperties.getNodeId(),
                membershipService.getMembershipSnapshot().stream().toList(),
                membershipService.getTopologyVersion()
        )));
        responseObserver.onCompleted();
    }

    private void refreshSourceHeartbeat(GossipRequest request) {
        if (request.sourceNodeId() == null || request.sourceNodeId().isBlank()) {
            return;
        }
        if (nodeProperties.getNodeId() != null && nodeProperties.getNodeId().equals(request.sourceNodeId())) {
            return;
        }

        MemberRecord sourceRecord = request.membership().stream()
                .filter(record -> request.sourceNodeId().equals(record.nodeId()))
                .findFirst()
                .orElse(null);

        String sourceAddress = sourceRecord != null ? sourceRecord.address() : null;
        long sourceIncarnation = sourceRecord != null ? sourceRecord.incarnation() : 0L;
        membershipService.updateHeartbeat(request.sourceNodeId(), sourceAddress, sourceIncarnation);
    }
}
