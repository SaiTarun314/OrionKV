package com.orionkv.coordinationplane.rpc;

import com.orionkv.coordinationplane.model.ReplicaReadResult;
import com.orionkv.dataplane.model.ReplicaRecord;
import com.orionkv.proto.ReplicaDataRpcGrpc;
import com.orionkv.proto.ReplicaGetRequest;
import com.orionkv.proto.ReplicaGetResponse;
import com.orionkv.proto.ReplicaPutRequest;
import com.orionkv.proto.ReplicaPutResponse;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class GrpcReplicaDataClient implements ReplicaDataClient {

    private static final long REQUEST_TIMEOUT_MS = 600;

    @Override
    public boolean putReplica(String targetAddress, String requestId, ReplicaRecord record) {
        ManagedChannel channel = buildChannel(targetAddress);
        try {
            ReplicaPutRequest request = ReplicaPutRequest.newBuilder()
                    .setRequestId(requestId)
                    .setKey(record.key())
                    .setValue(record.value() == null ? "" : record.value())
                    .setTimestamp(record.timestamp())
                    .setTombstone(record.tombstone())
                    .setToken(record.token())
                    .setSourceNodeId(record.sourceNodeId() == null ? "" : record.sourceNodeId())
                    .build();

            ReplicaPutResponse response = ReplicaDataRpcGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .putReplica(request);

            return response.getAck();
        } catch (StatusRuntimeException ex) {
            return false;
        } finally {
            channel.shutdownNow();
        }
    }

    @Override
    public ReplicaReadResult getReplica(String targetAddress, String requestId, String key) {
        ManagedChannel channel = buildChannel(targetAddress);
        try {
            ReplicaGetRequest request = ReplicaGetRequest.newBuilder()
                    .setRequestId(requestId)
                    .setKey(key)
                    .build();

            ReplicaGetResponse response = ReplicaDataRpcGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .getReplica(request);

            return new ReplicaReadResult(
                    response.getResponded(),
                    response.getFound(),
                    response.getKey(),
                    response.getValue(),
                    response.getToken(),
                    response.getTimestamp(),
                    response.getTombstone(),
                    response.getNodeId()
            );
        } catch (StatusRuntimeException ex) {
            return new ReplicaReadResult(false, false, key, null, -1L, -1L, false, null);
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
