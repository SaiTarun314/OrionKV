package com.orionkv.common.dto;

import com.orionkv.membership.model.MemberRecord;

import java.util.List;

public record GossipRequest(
        String sourceNodeId,
        List<MemberRecord> membership
) {
}
