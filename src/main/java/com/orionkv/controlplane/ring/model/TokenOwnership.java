package com.orionkv.controlplane.ring.model;

public record TokenOwnership(
        long token,
        String ownerNodeId
) {
}
