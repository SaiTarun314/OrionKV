package com.orionkv.clientrouter.dto;

public record JoinSeedResponse(
        boolean bootstrapSelf,
        String seedNodeId,
        String seedGrpcAddress,
        int knownAliveNodes,
        long topologyVersion
) {
}
