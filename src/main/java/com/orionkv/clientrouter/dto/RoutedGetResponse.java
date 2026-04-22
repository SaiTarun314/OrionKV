package com.orionkv.clientrouter.dto;

import java.util.List;

public record RoutedGetResponse(
        boolean found,
        String key,
        String value,
        long token,
        long timestamp,
        boolean tombstone,
        int responseCount,
        int requiredResponses,
        List<String> replicaNodeIds,
        String message,
        String contactedNodeId,
        String contactedGrpcAddress
) {
}
