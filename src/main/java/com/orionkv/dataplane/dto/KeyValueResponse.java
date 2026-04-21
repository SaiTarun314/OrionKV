package com.orionkv.dataplane.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.orionkv.dataplane.model.StoredValue;

public record KeyValueResponse(
        String key,
        String value,
        long timestamp,
        @JsonProperty("is_deleted")
        boolean isDeleted,
        long token
) {

    public static KeyValueResponse from(StoredValue storedValue) {
        return new KeyValueResponse(
                storedValue.key(),
                storedValue.value(),
                storedValue.timestamp(),
                storedValue.tombstone(),
                storedValue.token()
        );
    }
}
