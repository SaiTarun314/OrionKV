package com.example.kvstore.dataplane.model;

public record StoredValue(
    String key,
    String value,
    long timestamp,
    boolean tombstone,
    long token
) {
}
