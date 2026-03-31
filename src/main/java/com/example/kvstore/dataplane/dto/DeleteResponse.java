package com.example.kvstore.dataplane.dto;

public record DeleteResponse(
    String key,
    long timestamp,
    String status
) {
}
