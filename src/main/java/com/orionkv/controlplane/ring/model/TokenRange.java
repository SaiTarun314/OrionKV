package com.orionkv.controlplane.ring.model;

public record TokenRange(
        long startExclusive,
        long endInclusive,
        String ownerNodeId
) {
}
