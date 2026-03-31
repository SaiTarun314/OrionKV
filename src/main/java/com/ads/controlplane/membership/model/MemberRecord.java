package com.ads.controlplane.membership.model;

import java.time.Instant;

public record MemberRecord(
        String nodeId,
        String address,
        MemberStatus status,
        long incarnation,
        Instant lastSeen
) {
}
