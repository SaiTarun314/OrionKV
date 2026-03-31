package com.ads.controlplane.common.dto;

import com.ads.controlplane.membership.model.MemberRecord;

import java.util.List;

public record GossipResponse(
        String responderNodeId,
        List<MemberRecord> membership
) {
}
