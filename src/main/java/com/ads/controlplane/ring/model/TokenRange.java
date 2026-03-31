package com.ads.controlplane.ring.model;

public record TokenRange(
        long startExclusive,
        long endInclusive,
        String ownerNodeId
) {
}
