package com.example.kvstore.dataplane.service;

import com.example.kvstore.dataplane.model.StoredValue;

public record ReplicaApplyResult(
    StoredValue storedValue,
    boolean applied
) {
}
