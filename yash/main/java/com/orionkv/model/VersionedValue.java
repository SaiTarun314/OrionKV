package com.orionkv.model;

import java.util.Arrays;
import java.util.Objects;

public final class VersionedValue {
    private final byte[] value;
    private final long timestampEpochMs;
    private final String coordinatorNodeId;

    public VersionedValue(byte[] value, long timestampEpochMs, String coordinatorNodeId) {
        this.value = Objects.requireNonNull(value, "value");
        this.timestampEpochMs = timestampEpochMs;
        this.coordinatorNodeId = Objects.requireNonNull(coordinatorNodeId, "coordinatorNodeId");
    }

    public byte[] value() {
        return Arrays.copyOf(value, value.length);
    }

    public long timestampEpochMs() {
        return timestampEpochMs;
    }

    public String coordinatorNodeId() {
        return coordinatorNodeId;
    }
}
