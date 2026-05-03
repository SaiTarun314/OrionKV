package com.orionkv.controlplane.bootstrap.model;

import java.time.Instant;

public record RebalanceTimingSnapshot(
        String eventType,
        String triggerNodeId,
        String sourceNodeId,
        String status,
        int ranges,
        long durationMs,
        Instant startedAt,
        Instant endedAt,
        String error
) {
    public static RebalanceTimingSnapshot none() {
        return new RebalanceTimingSnapshot(
                "NONE",
                null,
                null,
                "NONE",
                0,
                0L,
                null,
                null,
                null
        );
    }
}
