package com.orionkv.dataplane.dto;

import com.orionkv.dataplane.model.StoredValue;

public record ReplicaWriteResponse(
        boolean applied,
        ReplicaRecordResponse record
) {
    public static ReplicaWriteResponse applied(StoredValue storedValue) {
        return new ReplicaWriteResponse(true, ReplicaRecordResponse.from(storedValue));
    }

    public static ReplicaWriteResponse ignored(StoredValue storedValue) {
        return new ReplicaWriteResponse(false, ReplicaRecordResponse.from(storedValue));
    }
}
