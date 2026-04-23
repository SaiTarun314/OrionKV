package com.orionkv.controlplane.bootstrap.service;

import com.orionkv.controlplane.bootstrap.model.RebalanceTimingSnapshot;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

public final class RebalanceMetricsRegistry {

    private static final AtomicReference<RebalanceTimingSnapshot> LATEST =
            new AtomicReference<>(RebalanceTimingSnapshot.none());

    private RebalanceMetricsRegistry() {
    }

    public static RebalanceToken begin(String eventType, String triggerNodeId, String sourceNodeId, int ranges) {
        Instant now = Instant.now();
        RebalanceToken token = new RebalanceToken(eventType, triggerNodeId, sourceNodeId, ranges, now, System.nanoTime());
        LATEST.set(new RebalanceTimingSnapshot(
                eventType,
                triggerNodeId,
                sourceNodeId,
                "IN_PROGRESS",
                ranges,
                0L,
                now,
                null,
                null
        ));
        return token;
    }

    public static void complete(RebalanceToken token) {
        if (token == null) {
            return;
        }
        long durationMs = Math.max(0L, (System.nanoTime() - token.startedNano()) / 1_000_000L);
        LATEST.set(new RebalanceTimingSnapshot(
                token.eventType(),
                token.triggerNodeId(),
                token.sourceNodeId(),
                "SUCCESS",
                token.ranges(),
                durationMs,
                token.startedAt(),
                Instant.now(),
                null
        ));
    }

    public static void fail(RebalanceToken token, Exception exception) {
        if (token == null) {
            return;
        }
        long durationMs = Math.max(0L, (System.nanoTime() - token.startedNano()) / 1_000_000L);
        LATEST.set(new RebalanceTimingSnapshot(
                token.eventType(),
                token.triggerNodeId(),
                token.sourceNodeId(),
                "FAILED",
                token.ranges(),
                durationMs,
                token.startedAt(),
                Instant.now(),
                exception == null ? "unknown" : exception.getMessage()
        ));
    }

    public static RebalanceTimingSnapshot latest() {
        return LATEST.get();
    }

    public record RebalanceToken(
            String eventType,
            String triggerNodeId,
            String sourceNodeId,
            int ranges,
            Instant startedAt,
            long startedNano
    ) {
    }
}
