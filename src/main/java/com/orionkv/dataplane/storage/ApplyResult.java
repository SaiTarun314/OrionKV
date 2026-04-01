package com.orionkv.dataplane.storage;

import com.orionkv.dataplane.model.StoredValue;

public record ApplyResult(
        StoredValue storedValue,
        boolean applied
) {
}
