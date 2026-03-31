package com.ads.controlplane.common.rpc;

import com.ads.controlplane.common.dto.GossipRequest;
import com.ads.controlplane.common.dto.GossipResponse;
import com.ads.controlplane.common.dto.JoinRequest;
import com.ads.controlplane.proto.ClusterRpcGrpc;
import com.ads.controlplane.proto.GossipRpcGrpc;
import com.google.protobuf.Empty;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.stereotype.Component;

@Component
public class GrpcControlPlaneClient implements ControlPlaneClient {

    @Override
    public GossipResponse gossip(String peerAddress, GossipRequest request) {
        ManagedChannel channel = buildChannel(peerAddress);
        try {
            return ProtoMapper.fromProto(GossipRpcGrpc.newBlockingStub(channel).gossip(ProtoMapper.toProto(request)));
        } finally {
            channel.shutdownNow();
        }
    }

    @Override
    public GossipResponse join(String seedAddress, JoinRequest request) {
        ManagedChannel channel = buildChannel(seedAddress);
        try {
            return ProtoMapper.fromProto(ClusterRpcGrpc.newBlockingStub(channel).join(ProtoMapper.toProto(request)));
        } finally {
            channel.shutdownNow();
        }
    }

    @Override
    public GossipResponse getMembership(String peerAddress) {
        ManagedChannel channel = buildChannel(peerAddress);
        try {
            return ProtoMapper.fromProto(
                    ClusterRpcGrpc.newBlockingStub(channel).getMembership(Empty.getDefaultInstance())
            );
        } finally {
            channel.shutdownNow();
        }
    }

    private ManagedChannel buildChannel(String address) {
        return ManagedChannelBuilder.forTarget(address.replaceFirst("^https?://", ""))
                .usePlaintext()
                .build();
    }
}
