package com.orionkv.dataplane.service;

import com.orionkv.dataplane.model.StoredValue;

public record ReplicaApplyResult(
        StoredValue storedValue,
        boolean applied
) {
}
