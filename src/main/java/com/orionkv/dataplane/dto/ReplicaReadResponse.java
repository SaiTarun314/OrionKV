package com.orionkv.dataplane.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.orionkv.dataplane.model.StoredValue;

public record ReplicaReadResponse(
        String key,
        String value,
        long timestamp,
        @JsonProperty("is_deleted")
        boolean isDeleted,
        long token,
        boolean found
) {
    public static ReplicaReadResponse from(StoredValue storedValue) {
        return new ReplicaReadResponse(
                storedValue.key(),
                storedValue.value(),
                storedValue.timestamp(),
                storedValue.tombstone(),
                storedValue.token(),
                true
        );
    }

    public static ReplicaReadResponse missing(String key) {
        return new ReplicaReadResponse(key, null, -1L, false, 0L, false);
    }
}
