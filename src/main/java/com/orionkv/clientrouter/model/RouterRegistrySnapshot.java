package com.orionkv.clientrouter.model;

import java.time.Instant;
import java.util.List;

public record RouterRegistrySnapshot(
        long topologyVersion,
        Instant updatedAt,
        List<RouterNodeRecord> nodes
) {
}
