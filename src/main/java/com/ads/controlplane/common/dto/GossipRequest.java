package com.ads.controlplane.common.dto;

import com.ads.controlplane.membership.model.MemberRecord;

import java.util.List;

public record GossipRequest(
        String sourceNodeId,
        List<MemberRecord> membership
) {
}
