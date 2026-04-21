package com.orionkv.controlplane.bootstrap.service;

import com.orionkv.dataplane.model.StoredValue;
import com.orionkv.dataplane.service.ReplicaStreamPage;
import com.orionkv.proto.ReplicaDataRpcGrpc;
import com.orionkv.proto.ReplicaRangeRequest;
import com.orionkv.proto.ReplicaRangeResponse;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class GrpcBootstrapReplicaClient implements BootstrapReplicaClient {

    private static final long REQUEST_TIMEOUT_MS = 1500L;
    private static final int DEFAULT_BATCH_SIZE = 100;

    @Override
    public ReplicaStreamPage streamRange(String address, long startToken, long endToken, int batchSize, String cursor) {
        ManagedChannel channel = buildChannel(address);
        try {
            ReplicaRangeRequest.Builder request = ReplicaRangeRequest.newBuilder()
                    .setStartToken(startToken)
                    .setEndToken(endToken)
                    .setBatchSize(batchSize > 0 ? batchSize : DEFAULT_BATCH_SIZE);

            if (cursor != null && !cursor.isBlank()) {
                request.setCursor(cursor);
            }

            ReplicaRangeResponse response = ReplicaDataRpcGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .streamRange(request.build());

            List<StoredValue> entries = response.getRecordsList().stream()
                    .map(record -> new StoredValue(
                            record.getKey(),
                            record.getTombstone() ? null : record.getValue(),
                            record.getTimestamp(),
                            record.getTombstone(),
                            record.getToken(),
                            record.getSourceNodeId().isBlank() ? null : record.getSourceNodeId()
                    ))
                    .toList();

            String nextCursor = response.getNextCursor().isBlank() ? null : response.getNextCursor();
            return new ReplicaStreamPage(entries, nextCursor, response.getDone());
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
