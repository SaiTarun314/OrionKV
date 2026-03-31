package com.example.kvstore.dataplane.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record WriteAheadLogEntry(
    OperationType operationType,
    String key,
    String value,
    long timestamp,
    boolean tombstone,
    long token
) {

    public static WriteAheadLogEntry put(String key, String value, long timestamp, long token) {
        return new WriteAheadLogEntry(OperationType.PUT, key, value, timestamp, false, token);
    }

    public static WriteAheadLogEntry delete(String key, long timestamp, long token) {
        return new WriteAheadLogEntry(OperationType.DELETE, key, null, timestamp, true, token);
    }

    public enum OperationType {
        PUT,
        DELETE
    }
}
