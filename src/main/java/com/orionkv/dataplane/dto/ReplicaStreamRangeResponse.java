package com.orionkv.dataplane.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.orionkv.dataplane.service.ReplicaStreamPage;

import java.util.List;

public record ReplicaStreamRangeResponse(
        List<ReplicaVersionResponse> entries,
        @JsonProperty("next_cursor")
        String nextCursor,
        boolean done
) {
    public static ReplicaStreamRangeResponse from(ReplicaStreamPage page) {
        return new ReplicaStreamRangeResponse(
                page.entries().stream().map(ReplicaVersionResponse::from).toList(),
                page.nextCursor(),
                page.done()
        );
    }
}
