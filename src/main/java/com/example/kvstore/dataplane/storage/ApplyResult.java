package com.example.kvstore.dataplane.storage;

import com.example.kvstore.dataplane.model.StoredValue;

public record ApplyResult(
    StoredValue storedValue,
    boolean applied
) {
}
