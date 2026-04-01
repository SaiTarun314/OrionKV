package com.orionkv.coordination;

import com.orionkv.model.VersionedValue;
import java.util.Optional;

public interface CoordinatorService {
    int handlePut(String requestId, String key, byte[] value);

    Optional<VersionedValue> handleGet(String requestId, String key);
}
