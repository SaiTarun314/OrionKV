package com.orionkv.clientrouter.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;

public record RouterNodeRecord(
        String nodeId,
        String grpcAddress,
        NodeStatus status,
        Instant lastSeen
) {
    @JsonIgnore
    public boolean isAlive() {
        return status == NodeStatus.ALIVE;
    }
}
