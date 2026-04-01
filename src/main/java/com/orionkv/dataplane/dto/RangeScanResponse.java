package com.orionkv.dataplane.dto;

import com.orionkv.dataplane.model.StoredValue;

import java.util.List;

public record RangeScanResponse(
        long startToken,
        long endToken,
        int count,
        List<RangeScanItemResponse> items
) {

    public static RangeScanResponse from(long startToken, long endToken, List<StoredValue> values) {
        return new RangeScanResponse(
                startToken,
                endToken,
                values.size(),
                values.stream().map(RangeScanItemResponse::from).toList()
        );
    }
}
