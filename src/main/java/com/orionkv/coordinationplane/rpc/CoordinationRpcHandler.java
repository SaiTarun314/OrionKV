package com.orionkv.coordinationplane.rpc;

import com.orionkv.coordinationplane.service.QuorumCoordinatorService;
import com.orionkv.proto.ClientGetRequest;
import com.orionkv.proto.ClientGetResponse;
import com.orionkv.proto.ClientPutRequest;
import com.orionkv.proto.ClientPutResponse;
import com.orionkv.proto.CoordinationRpcGrpc;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;

@Component
public class CoordinationRpcHandler extends CoordinationRpcGrpc.CoordinationRpcImplBase {

    private final QuorumCoordinatorService quorumCoordinatorService;

    public CoordinationRpcHandler(QuorumCoordinatorService quorumCoordinatorService) {
        this.quorumCoordinatorService = quorumCoordinatorService;
    }

    @Override
    public void put(ClientPutRequest request, StreamObserver<ClientPutResponse> responseObserver) {
        ClientPutResponse response = quorumCoordinatorService.put(
                request.getRequestId(),
                request.getKey(),
                request.getValue(),
                request.getTimestamp()
        );
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void get(ClientGetRequest request, StreamObserver<ClientGetResponse> responseObserver) {
        ClientGetResponse response = quorumCoordinatorService.get(request.getRequestId(), request.getKey());
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
