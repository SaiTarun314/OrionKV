package com.example.kvstore.dataplane.dto;

import com.example.kvstore.dataplane.model.StoredValue;

import java.util.List;

public record RangeScanResponse(
    long startToken,
    long endToken,
    int count,
    List<RangeScanItemResponse> items
) {

    public static RangeScanResponse from(List<StoredValue> values) {
        long startToken = values.isEmpty() ? 0L : values.getFirst().token();
        long endToken = values.isEmpty() ? 0L : values.getLast().token();

        return new RangeScanResponse(
            startToken,
            endToken,
            values.size(),
            values.stream().map(RangeScanItemResponse::from).toList()
        );
    }

    public static RangeScanResponse from(long startToken, long endToken, List<StoredValue> values) {
        return new RangeScanResponse(
            startToken,
            endToken,
            values.size(),
            values.stream().map(RangeScanItemResponse::from).toList()
        );
    }
}
