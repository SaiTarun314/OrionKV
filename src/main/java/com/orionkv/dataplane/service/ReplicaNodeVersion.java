package com.orionkv.dataplane.service;

import com.orionkv.dataplane.model.StoredValue;

public record ReplicaNodeVersion(
        String replicaBaseUrl,
        StoredValue version
) {
}
