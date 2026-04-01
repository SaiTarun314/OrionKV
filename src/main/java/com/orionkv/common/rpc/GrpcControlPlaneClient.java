package com.orionkv.common.rpc;

import com.orionkv.common.dto.GossipRequest;
import com.orionkv.common.dto.GossipResponse;
import com.orionkv.common.dto.JoinRequest;
import com.orionkv.proto.ClusterRpcGrpc;
import com.orionkv.proto.GossipRpcGrpc;
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
