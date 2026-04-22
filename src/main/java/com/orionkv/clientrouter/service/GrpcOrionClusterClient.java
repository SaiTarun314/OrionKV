package com.orionkv.clientrouter.service;

import com.google.protobuf.Empty;
import com.orionkv.clientrouter.config.ClientRouterProperties;
import com.orionkv.proto.ClientDeleteRequest;
import com.orionkv.proto.ClientDeleteResponse;
import com.orionkv.proto.ClientGetRequest;
import com.orionkv.proto.ClientGetResponse;
import com.orionkv.proto.ClientPutRequest;
import com.orionkv.proto.ClientPutResponse;
import com.orionkv.proto.ClusterRpcGrpc;
import com.orionkv.proto.CoordinationRpcGrpc;
import com.orionkv.proto.MembershipState;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class GrpcOrionClusterClient implements OrionClusterClient {

    private final ClientRouterProperties properties;

    public GrpcOrionClusterClient(ClientRouterProperties properties) {
        this.properties = properties;
    }

    @Override
    public MembershipState getMembership(String grpcAddress) {
        ManagedChannel channel = buildChannel(grpcAddress);
        try {
            return ClusterRpcGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(properties.getRpcTimeoutMs(), TimeUnit.MILLISECONDS)
                    .getMembership(Empty.getDefaultInstance());
        } finally {
            channel.shutdownNow();
        }
    }

    @Override
    public ClientPutResponse put(String grpcAddress, String requestId, String key, String value, long timestamp) {
        ManagedChannel channel = buildChannel(grpcAddress);
        try {
            return CoordinationRpcGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(properties.getRpcTimeoutMs(), TimeUnit.MILLISECONDS)
                    .put(ClientPutRequest.newBuilder()
                            .setRequestId(requestId)
                            .setKey(key)
                            .setValue(value)
                            .setTimestamp(timestamp)
                            .build());
        } finally {
            channel.shutdownNow();
        }
    }

    @Override
    public ClientGetResponse get(String grpcAddress, String requestId, String key) {
        ManagedChannel channel = buildChannel(grpcAddress);
        try {
            return CoordinationRpcGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(properties.getRpcTimeoutMs(), TimeUnit.MILLISECONDS)
                    .get(ClientGetRequest.newBuilder()
                            .setRequestId(requestId)
                            .setKey(key)
                            .build());
        } finally {
            channel.shutdownNow();
        }
    }

    @Override
    public ClientDeleteResponse delete(String grpcAddress, String requestId, String key, long timestamp) {
        ManagedChannel channel = buildChannel(grpcAddress);
        try {
            return CoordinationRpcGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(properties.getRpcTimeoutMs(), TimeUnit.MILLISECONDS)
                    .delete(ClientDeleteRequest.newBuilder()
                            .setRequestId(requestId)
                            .setKey(key)
                            .setTimestamp(timestamp)
                            .build());
        } finally {
            channel.shutdownNow();
        }
    }

    private ManagedChannel buildChannel(String grpcAddress) {
        return ManagedChannelBuilder.forTarget(grpcAddress.replaceFirst("^https?://", ""))
                .usePlaintext()
                .build();
    }
}
