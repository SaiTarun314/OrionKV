package com.example.kvstore.dataplane.dto;

import com.example.kvstore.dataplane.model.StoredValue;

import java.util.List;

public record ReplicaStreamResponse(
    long startToken,
    long endToken,
    int count,
    List<ReplicaRecordResponse> records
) {
    public static ReplicaStreamResponse from(long startToken, long endToken, List<StoredValue> values) {
        return new ReplicaStreamResponse(
            startToken,
            endToken,
            values.size(),
            values.stream().map(ReplicaRecordResponse::from).toList()
        );
    }
}
