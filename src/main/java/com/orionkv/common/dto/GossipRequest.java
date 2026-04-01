package com.orionkv.common.dto;

import com.orionkv.controlplane.membership.model.MemberRecord;

import java.util.List;

public record GossipRequest(
        String sourceNodeId,
        List<MemberRecord> membership
) {
}
