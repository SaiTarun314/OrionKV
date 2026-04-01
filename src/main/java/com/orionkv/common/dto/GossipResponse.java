package com.orionkv.common.dto;

import com.orionkv.membership.model.MemberRecord;

import java.util.List;

public record GossipResponse(
        String responderNodeId,
        List<MemberRecord> membership
) {
}
