package com.orionkv.clientrouter.model;

public record RoutedRequestResult<T>(
        String contactedNodeId,
        String contactedGrpcAddress,
        T response
) {
}
