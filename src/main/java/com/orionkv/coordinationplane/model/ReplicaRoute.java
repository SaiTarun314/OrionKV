package com.orionkv.coordinationplane.model;

import java.util.List;

public record ReplicaRoute(
        String key,
        long primaryToken,
        List<String> replicaNodeIds
) {
}
