package com.orionkv.clientrouter.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orionkv.clientrouter.config.ClientRouterProperties;
import com.orionkv.proto.ClientGetResponse;
import com.orionkv.proto.MembershipState;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KvRoutingServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldRetryNextAliveNodeWhenFirstRouteFails() {
        ClientRouterProperties properties = new ClientRouterProperties();
        properties.setRegistryPath(tempDir.resolve("kv-router.json").toString());

        NodeRegistryService registryService = new NodeRegistryService(
                new ObjectMapper().findAndRegisterModules(),
                properties,
                Clock.fixed(Instant.parse("2026-04-22T12:00:00Z"), ZoneOffset.UTC)
        );
        registryService.load();
        registryService.upsertManualNode("node-1", "10.0.0.1:9091");
        registryService.upsertManualNode("node-2", "10.0.0.2:9092");

        StubClusterClient clusterClient = new StubClusterClient();
        clusterClient.unavailableAddress = "10.0.0.1:9091";
        KvRoutingService routingService = new KvRoutingService(registryService, clusterClient);

        var routed = routingService.get("alpha");

        assertThat(routed.contactedNodeId()).isEqualTo("node-2");
        assertThat(routed.response().getFound()).isTrue();
        assertThat(clusterClient.addressesCalled).contains("10.0.0.2:9092");
    }

    private static final class StubClusterClient implements OrionClusterClient {
        private String unavailableAddress;
        private final List<String> addressesCalled = new ArrayList<>();

        @Override
        public MembershipState getMembership(String grpcAddress) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.orionkv.proto.ClientPutResponse put(String grpcAddress, String requestId, String key, String value, long timestamp) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ClientGetResponse get(String grpcAddress, String requestId, String key) {
            addressesCalled.add(grpcAddress);
            if (grpcAddress.equals(unavailableAddress)) {
                throw new StatusRuntimeException(Status.UNAVAILABLE);
            }
            return ClientGetResponse.newBuilder()
                    .setFound(true)
                    .setKey(key)
                    .setValue("value")
                    .setMessage("read quorum satisfied")
                    .build();
        }

        @Override
        public com.orionkv.proto.ClientDeleteResponse delete(String grpcAddress, String requestId, String key, long timestamp) {
            throw new UnsupportedOperationException();
        }
    }
}
