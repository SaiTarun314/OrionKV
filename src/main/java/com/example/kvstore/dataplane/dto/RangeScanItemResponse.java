package com.example.kvstore.dataplane.dto;

import com.example.kvstore.dataplane.model.StoredValue;

public record RangeScanItemResponse(
    String key,
    String value,
    long timestamp,
    long token
) {

    public static RangeScanItemResponse from(StoredValue storedValue) {
        return new RangeScanItemResponse(
            storedValue.key(),
            storedValue.value(),
            storedValue.timestamp(),
            storedValue.token()
        );
    }
}
