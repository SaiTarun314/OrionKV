package com.orionkv.dataplane.rpc;

import org.springframework.stereotype.Component;

import com.orionkv.config.NodeProperties;
import com.orionkv.dataplane.model.ReplicaRecord;
import com.orionkv.dataplane.service.ReplicaApplyResult;
import com.orionkv.dataplane.service.StorageService;
import com.orionkv.proto.ReplicaDataRpcGrpc;
import com.orionkv.proto.ReplicaGetRequest;
import com.orionkv.proto.ReplicaGetResponse;
import com.orionkv.proto.ReplicaPutRequest;
import com.orionkv.proto.ReplicaPutResponse;
import com.orionkv.proto.ReplicaRangeRequest;
import com.orionkv.proto.ReplicaRangeResponse;
import com.orionkv.proto.ReplicaRecordProto;

import io.grpc.stub.StreamObserver;

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

        storageService.getVersioned(request.getKey()).ifPresentOrElse(storedValue -> {
            builder.setFound(true)
                    .setKey(storedValue.key())
                    .setValue(storedValue.value() == null ? "" : storedValue.value())
                    .setToken(storedValue.token())
                    .setTimestamp(storedValue.timestamp())
                    .setTombstone(storedValue.tombstone())
                    .setMessage("found");
        }, () -> {
            builder.setFound(false)
                    .setKey(request.getKey())
                    .setTimestamp(-1L)
                    .setMessage("not found");
        });

        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void streamRange(ReplicaRangeRequest request, StreamObserver<ReplicaRangeResponse> responseObserver) {
        var page = storageService.scanRangePage(
                request.getStartToken(),
                request.getEndToken(),
                request.getBatchSize(),
                request.getCursor().isBlank() ? null : request.getCursor()
        );

        ReplicaRangeResponse.Builder builder = ReplicaRangeResponse.newBuilder()
                .setDone(page.done());

        if (page.nextCursor() != null && !page.nextCursor().isBlank()) {
            builder.setNextCursor(page.nextCursor());
        }

        page.entries().forEach(entry -> builder.addRecords(ReplicaRecordProto.newBuilder()
                .setKey(entry.key())
                .setValue(entry.value() == null ? "" : entry.value())
                .setTimestamp(entry.timestamp())
                .setTombstone(entry.tombstone())
                .setToken(entry.token())
                .setSourceNodeId(entry.sourceNodeId() == null ? "" : entry.sourceNodeId())
                .build()));

        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }
}
