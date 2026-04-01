package com.orionkv.api;

import com.orionkv.coordination.CoordinatorService;
import com.orionkv.model.VersionedValue;
import com.orionkv.proto.ClientServiceGrpc;
import com.orionkv.proto.GetRequest;
import com.orionkv.proto.GetResponse;
import com.orionkv.proto.PutRequest;
import com.orionkv.proto.PutResponse;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import java.util.Optional;

public final class ClientGrpcService extends ClientServiceGrpc.ClientServiceImplBase {
    private final String localNodeId;
    private final int writeQuorumW;
    private final int readQuorumR;
    private final CoordinatorService coordinator;

    public ClientGrpcService(String localNodeId, int writeQuorumW, int readQuorumR, CoordinatorService coordinator) {
        this.localNodeId = Objects.requireNonNull(localNodeId, "localNodeId");
        this.writeQuorumW = writeQuorumW;
        this.readQuorumR = readQuorumR;
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    @Override
    public void put(PutRequest request, StreamObserver<PutResponse> responseObserver) {
        int ackCount = coordinator.handlePut(request.getRequestId(), request.getKey(), request.getValue().toByteArray());
        boolean ok = ackCount >= writeQuorumW;

        PutResponse response = PutResponse.newBuilder()
                .setSuccess(ok)
                .setCoordinatorNodeId(localNodeId)
                .setAckCount(ackCount)
                .setMessage(ok ? "write quorum satisfied" : "write quorum not met")
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void get(GetRequest request, StreamObserver<GetResponse> responseObserver) {
        Optional<VersionedValue> result = coordinator.handleGet(request.getRequestId(), request.getKey());

        GetResponse.Builder builder = GetResponse.newBuilder();
        if (result.isPresent()) {
            VersionedValue value = result.get();
            builder.setFound(true)
                    .setValue(com.google.protobuf.ByteString.copyFrom(value.value()))
                    .setTimestampEpochMs(value.timestampEpochMs())
                    .setWinningNodeId(value.coordinatorNodeId())
                    .setMessage("read quorum satisfied");
        } else {
            builder.setFound(false)
                    .setMessage("read quorum not met. required responses=" + readQuorumR);
        }

        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }
}
