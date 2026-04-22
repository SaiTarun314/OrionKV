package com.orionkv.clientrouter.dto;

import java.util.List;

public record RoutedPutResponse(
        boolean success,
        String key,
        long token,
        long timestamp,
        int ackCount,
        int requiredAcks,
        List<String> replicaNodeIds,
        String message,
        String contactedNodeId,
        String contactedGrpcAddress
) {
}
