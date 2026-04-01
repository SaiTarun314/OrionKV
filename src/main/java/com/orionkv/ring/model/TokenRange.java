package com.orionkv.ring.model;

public record TokenRange(
        long startExclusive,
        long endInclusive,
        String ownerNodeId
) {
}
