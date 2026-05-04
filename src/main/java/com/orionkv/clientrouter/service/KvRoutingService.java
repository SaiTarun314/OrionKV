package com.orionkv.clientrouter.service;

import com.orionkv.clientrouter.model.RouterNodeRecord;
import com.orionkv.clientrouter.model.RoutedRequestResult;
import com.orionkv.proto.ClientDeleteResponse;
import com.orionkv.proto.ClientGetResponse;
import com.orionkv.proto.ClientPutResponse;
import io.grpc.StatusRuntimeException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

@Service
public class KvRoutingService {

    private final NodeRegistryService nodeRegistryService;
    private final OrionClusterClient clusterClient;

    public KvRoutingService(NodeRegistryService nodeRegistryService, OrionClusterClient clusterClient) {
        this.nodeRegistryService = nodeRegistryService;
        this.clusterClient = clusterClient;
    }

    public RoutedRequestResult<ClientPutResponse> put(String key, String value, Long timestamp) {
        long resolvedTimestamp = timestamp == null ? Instant.now().toEpochMilli() : timestamp;
        return route(node -> clusterClient.put(node.grpcAddress(), requestId(), key, value, resolvedTimestamp));
    }

    public RoutedRequestResult<ClientGetResponse> get(String key) {
        return route(node -> clusterClient.get(node.grpcAddress(), requestId(), key));
    }

    public RoutedRequestResult<ClientDeleteResponse> delete(String key, Long timestamp) {
        long resolvedTimestamp = timestamp == null ? Instant.now().toEpochMilli() : timestamp;
        return route(node -> clusterClient.delete(node.grpcAddress(), requestId(), key, resolvedTimestamp));
    }

    private <T> RoutedRequestResult<T> route(Function<RouterNodeRecord, T> request) {
        List<RouterNodeRecord> aliveNodes = nodeRegistryService.aliveNodes();
        if (aliveNodes.isEmpty()) {
            throw new IllegalStateException("No alive OrionKV nodes available in the client router table");
        }

        List<RouterNodeRecord> candidateNodes = new ArrayList<>(aliveNodes);
        Collections.shuffle(candidateNodes, ThreadLocalRandom.current());

        RuntimeException lastFailure = null;
        for (RouterNodeRecord node : candidateNodes) {
            try {
                T response = request.apply(node);
                return new RoutedRequestResult<>(node.nodeId(), node.grpcAddress(), response);
            } catch (StatusRuntimeException | IllegalStateException ex) {
                lastFailure = ex;
            }
        }

        throw new IllegalStateException("Could not route request to any alive OrionKV node", lastFailure);
    }

    private String requestId() {
        return UUID.randomUUID().toString();
    }
}
