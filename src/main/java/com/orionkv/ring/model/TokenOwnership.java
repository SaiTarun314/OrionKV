package com.orionkv.ring.model;

public record TokenOwnership(
        long token,
        String ownerNodeId
) {
}
