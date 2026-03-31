package com.example.kvstore.dataplane.model;

public record ReplicaRecord(
    String key,
    String value,
    long timestamp,
    boolean tombstone,
    long token,
    String sourceNodeId
) {
}
