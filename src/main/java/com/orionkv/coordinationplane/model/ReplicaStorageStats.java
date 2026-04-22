package com.orionkv.coordinationplane.model;

public record ReplicaStorageStats(
        boolean reachable,
        long totalRecords,
        long liveRecords,
        long tombstoneRecords
) {
}
