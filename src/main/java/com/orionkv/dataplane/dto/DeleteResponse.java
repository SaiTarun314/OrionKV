package com.orionkv.dataplane.dto;

public record DeleteResponse(
        String key,
        long timestamp,
        String status
) {
}
