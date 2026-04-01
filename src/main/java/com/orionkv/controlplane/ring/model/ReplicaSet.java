package com.orionkv.controlplane.ring.model;

import java.util.List;

public record ReplicaSet(
        long token,
        List<String> replicaNodeIds
) {
}
