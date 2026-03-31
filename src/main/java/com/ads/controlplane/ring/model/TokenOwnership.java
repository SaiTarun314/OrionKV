package com.ads.controlplane.ring.model;

public record TokenOwnership(
        long token,
        String ownerNodeId
) {
}
