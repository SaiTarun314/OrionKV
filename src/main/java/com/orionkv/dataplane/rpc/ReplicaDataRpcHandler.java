package com.orionkv.dataplane.rpc;

import com.orionkv.config.NodeProperties;
import com.orionkv.dataplane.exception.KeyNotFoundException;
import com.orionkv.dataplane.model.ReplicaRecord;
import com.orionkv.dataplane.model.StoredValue;
import com.orionkv.dataplane.service.ReplicaApplyResult;
import com.orionkv.dataplane.service.StorageService;
import com.orionkv.proto.ReplicaDataRpcGrpc;
import com.orionkv.proto.ReplicaGetRequest;
import com.orionkv.proto.ReplicaGetResponse;
import com.orionkv.proto.ReplicaPutRequest;
import com.orionkv.proto.ReplicaPutResponse;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;

@Component
public class ReplicaDataRpcHandler extends ReplicaDataRpcGrpc.ReplicaDataRpcImplBase {

    private final StorageService storageService;
    private final NodeProperties nodeProperties;

    public ReplicaDataRpcHandler(StorageService storageService, NodeProperties nodeProperties) {
        this.storageService = storageService;
        this.nodeProperties = nodeProperties;
    }

    @Override
    public void putReplica(ReplicaPutRequest request, StreamObserver<ReplicaPutResponse> responseObserver) {
        ReplicaRecord record = new ReplicaRecord(
                request.getKey(),
                request.getTombstone() ? null : request.getValue(),
                request.getTimestamp(),
                request.getTombstone(),
                request.getToken(),
                request.getSourceNodeId()
        );

        ReplicaApplyResult result = storageService.applyReplicaWrite(record);
        responseObserver.onNext(ReplicaPutResponse.newBuilder()
                .setAck(true)
                .setApplied(result.applied())
                .setNodeId(nodeProperties.getNodeId() == null ? "" : nodeProperties.getNodeId())
                .setMessage(result.applied() ? "applied" : "ignored")
                .build());
        responseObserver.onCompleted();
    }

    @Override
    public void getReplica(ReplicaGetRequest request, StreamObserver<ReplicaGetResponse> responseObserver) {
        ReplicaGetResponse.Builder builder = ReplicaGetResponse.newBuilder()
                .setResponded(true)
                .setNodeId(nodeProperties.getNodeId() == null ? "" : nodeProperties.getNodeId());

        try {
            StoredValue storedValue = storageService.get(request.getKey());
            builder.setFound(true)
                    .setKey(storedValue.key())
                    .setValue(storedValue.value() == null ? "" : storedValue.value())
                    .setToken(storedValue.token())
                    .setTimestamp(storedValue.timestamp())
                    .setTombstone(storedValue.tombstone())
                    .setMessage("found");
        } catch (KeyNotFoundException ignored) {
            builder.setFound(false)
                    .setKey(request.getKey())
                    .setMessage("not found");
        }

        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }
}
