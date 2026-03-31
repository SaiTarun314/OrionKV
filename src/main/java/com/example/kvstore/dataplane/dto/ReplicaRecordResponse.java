package com.example.kvstore.dataplane.dto;

import com.example.kvstore.dataplane.model.ReplicaRecord;
import com.example.kvstore.dataplane.model.StoredValue;

public record ReplicaRecordResponse(
    String key,
    String value,
    long timestamp,
    boolean tombstone,
    long token,
    String sourceNodeId
) {
    public static ReplicaRecordResponse from(StoredValue storedValue) {
        return new ReplicaRecordResponse(
            storedValue.key(),
            storedValue.value(),
            storedValue.timestamp(),
            storedValue.tombstone(),
            storedValue.token(),
            null
        );
    }

    public static ReplicaRecordResponse from(ReplicaRecord replicaRecord) {
        return new ReplicaRecordResponse(
            replicaRecord.key(),
            replicaRecord.value(),
            replicaRecord.timestamp(),
            replicaRecord.tombstone(),
            replicaRecord.token(),
            replicaRecord.sourceNodeId()
        );
    }
}
