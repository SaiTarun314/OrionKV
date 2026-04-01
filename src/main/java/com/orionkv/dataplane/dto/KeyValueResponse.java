package com.orionkv.dataplane.dto;

import com.orionkv.dataplane.model.StoredValue;

public record KeyValueResponse(
        String key,
        String value,
        long timestamp,
        boolean tombstone,
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
