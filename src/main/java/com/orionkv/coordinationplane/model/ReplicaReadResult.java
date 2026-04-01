package com.orionkv.coordinationplane.model;

public record ReplicaReadResult(
        boolean responded,
        boolean found,
        String key,
        String value,
        long token,
        long timestamp,
        boolean tombstone,
        String nodeId
) {
}
