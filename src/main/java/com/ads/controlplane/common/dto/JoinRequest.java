package com.ads.controlplane.common.dto;

public record JoinRequest(
        String nodeId,
        String address
) {
}
