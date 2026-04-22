package com.orionkv.dataplane.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record WriteAheadLogEntry(
        OperationType operationType,
        String key,
        String value,
        long timestamp,
        boolean tombstone,
        long token,
        String sourceNodeId
) {

    public static WriteAheadLogEntry put(String key, String value, long timestamp, long token) {
        return put(key, value, timestamp, token, null);
    }

    public static WriteAheadLogEntry put(String key, String value, long timestamp, long token, String sourceNodeId) {
        return new WriteAheadLogEntry(OperationType.PUT, key, value, timestamp, false, token, sourceNodeId);
    }

    public static WriteAheadLogEntry delete(String key, long timestamp, long token) {
        return delete(key, timestamp, token, null);
    }

    public static WriteAheadLogEntry delete(String key, long timestamp, long token, String sourceNodeId) {
        return new WriteAheadLogEntry(OperationType.DELETE, key, null, timestamp, true, token, sourceNodeId);
    }

    public enum OperationType {
        PUT,
        DELETE
    }
}
