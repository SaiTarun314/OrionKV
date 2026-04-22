package com.orionkv.controlplane.bootstrap.service;

import com.orionkv.clientrouter.dto.JoinSeedResponse;
import com.orionkv.clientrouter.dto.NodeRegistrationRequest;
import com.orionkv.config.NodeProperties;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class ClientRouterSeedClient {

    private final RestClient restClient;
    private final NodeProperties nodeProperties;

    @Autowired
    public ClientRouterSeedClient(RestClient.Builder restClientBuilder, NodeProperties nodeProperties) {
        this(restClientBuilder.build(), nodeProperties);
    }

    public ClientRouterSeedClient(RestClient restClient, NodeProperties nodeProperties) {
        this.restClient = restClient;
        this.nodeProperties = nodeProperties;
    }

    public Optional<JoinSeedResponse> resolveJoinSeed() {
        if (nodeProperties.getClientRouterBaseUrl() == null || nodeProperties.getClientRouterBaseUrl().isBlank()) {
            return Optional.empty();
        }

        NodeRegistrationRequest request = new NodeRegistrationRequest();
        request.setNodeId(nodeProperties.getNodeId());
        request.setGrpcAddress(nodeProperties.getAddress());

        JoinSeedResponse response = restClient.post()
                .uri(joinSeedUri())
                .body(request)
                .retrieve()
                .body(JoinSeedResponse.class);
        return Optional.ofNullable(response);
    }

    private String joinSeedUri() {
        String baseUrl = nodeProperties.getClientRouterBaseUrl();
        if (baseUrl.endsWith("/")) {
            return baseUrl + "client/nodes/seed";
        }
        return baseUrl + "/client/nodes/seed";
    }
}
