package com.orionkv.controlplane.bootstrap.model;

public record PrimaryOwnershipMovement(
        long startToken,
        long endToken,
        String sourceNodeId,
        String targetNodeId
) {
}
