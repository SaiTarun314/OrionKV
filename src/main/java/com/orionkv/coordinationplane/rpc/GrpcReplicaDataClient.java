package com.orionkv.coordinationplane.rpc;

import com.orionkv.coordinationplane.model.ReplicaReadResult;
import com.orionkv.coordinationplane.model.ReplicaStorageStats;
import com.orionkv.dataplane.model.ReplicaRecord;
import com.orionkv.proto.ReplicaDataRpcGrpc;
import com.orionkv.proto.ReplicaGetRequest;
import com.orionkv.proto.ReplicaGetResponse;
import com.orionkv.proto.ReplicaPutRequest;
import com.orionkv.proto.ReplicaPutResponse;
import com.orionkv.proto.ReplicaRangeRequest;
import com.orionkv.proto.ReplicaRangeResponse;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class GrpcReplicaDataClient implements ReplicaDataClient {

    private static final long REQUEST_TIMEOUT_MS = 600;
    private static final long RANGE_REQUEST_TIMEOUT_MS = 3000;
    private static final int RANGE_BATCH_SIZE = 4000;

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

    @Override
    public ReplicaStorageStats getReplicaStorageStats(String targetAddress, String requestId) {
        ManagedChannel channel = buildChannel(targetAddress);
        try {
            ReplicaDataRpcGrpc.ReplicaDataRpcBlockingStub stub = ReplicaDataRpcGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(RANGE_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            String cursor = "";
            AtomicLong total = new AtomicLong();
            AtomicLong live = new AtomicLong();
            AtomicLong tombstones = new AtomicLong();

            while (true) {
                ReplicaRangeRequest request = ReplicaRangeRequest.newBuilder()
                        .setStartToken(Long.MIN_VALUE)
                        .setEndToken(Long.MAX_VALUE)
                        .setBatchSize(RANGE_BATCH_SIZE)
                        .setCursor(cursor)
                        .build();

                ReplicaRangeResponse response = stub.streamRange(request);
                response.getRecordsList().forEach(record -> {
                    total.incrementAndGet();
                    if (record.getTombstone()) {
                        tombstones.incrementAndGet();
                    } else {
                        live.incrementAndGet();
                    }
                });

                if (response.getDone()) {
                    break;
                }
                cursor = response.getNextCursor();
            }

            return new ReplicaStorageStats(true, total.get(), live.get(), tombstones.get());
        } catch (StatusRuntimeException ex) {
            return new ReplicaStorageStats(false, 0L, 0L, 0L);
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
