package com.orionkv.clientrouter.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orionkv.clientrouter.config.ClientRouterProperties;
import com.orionkv.clientrouter.dto.JoinConfirmationResponse;
import com.orionkv.clientrouter.dto.JoinSeedResponse;
import com.orionkv.clientrouter.dto.NodeRegistrationRequest;
import com.orionkv.proto.MemberRecordProto;
import com.orionkv.proto.MemberStatusProto;
import com.orionkv.proto.MembershipState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class JoinRoutingServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldBootstrapFirstNodeAgainstItself() {
        NodeRegistryService registryService = registry(tempDir.resolve("first-node.json"));
        StubClusterClient clusterClient = new StubClusterClient();
        JoinRoutingService joinRoutingService = new JoinRoutingService(registryService, clusterClient, properties());

        NodeRegistrationRequest request = new NodeRegistrationRequest();
        request.setNodeId("node-1");
        request.setGrpcAddress("10.0.0.1:9091");

        JoinSeedResponse response = joinRoutingService.resolveSeed(request);

        assertThat(response.bootstrapSelf()).isTrue();
        assertThat(response.seedNodeId()).isEqualTo("node-1");
        assertThat(response.seedGrpcAddress()).isEqualTo("10.0.0.1:9091");
    }

    @Test
    void shouldUseExistingAliveNodeAsSeedAndConfirmJoin() {
        NodeRegistryService registryService = registry(tempDir.resolve("confirm-join.json"));
        registryService.upsertManualNode("node-1", "10.0.0.1:9091");

        StubClusterClient clusterClient = new StubClusterClient();
        clusterClient.membershipState = MembershipState.newBuilder()
                .setTopologyVersion(4)
                .addMembership(member("node-1", "10.0.0.1:9091", MemberStatusProto.ALIVE))
                .addMembership(member("node-2", "10.0.0.2:9092", MemberStatusProto.ALIVE))
                .build();

        JoinRoutingService joinRoutingService = new JoinRoutingService(registryService, clusterClient, properties());

        NodeRegistrationRequest request = new NodeRegistrationRequest();
        request.setNodeId("node-2");
        request.setGrpcAddress("10.0.0.2:9092");

        JoinSeedResponse seed = joinRoutingService.resolveSeed(request);
        JoinConfirmationResponse confirmation = joinRoutingService.confirmJoin("node-2", seed.seedGrpcAddress(), 1, 1L);

        assertThat(seed.bootstrapSelf()).isFalse();
        assertThat(seed.seedNodeId()).isEqualTo("node-1");
        assertThat(confirmation.confirmed()).isTrue();
        assertThat(confirmation.registry().nodes())
                .extracting(node -> node.nodeId() + ":" + node.grpcAddress())
                .containsExactly("node-1:10.0.0.1:9091", "node-2:10.0.0.2:9092");
    }

    @Test
    void shouldRefreshExistingNodeAddressWhenResolvingSeed() {
        NodeRegistryService registryService = registry(tempDir.resolve("refresh-address.json"));
        registryService.upsertManualNode("node-1", "node-1:9090");

        StubClusterClient clusterClient = new StubClusterClient();
        JoinRoutingService joinRoutingService = new JoinRoutingService(registryService, clusterClient, properties());

        NodeRegistrationRequest request = new NodeRegistrationRequest();
        request.setNodeId("node-1");
        request.setGrpcAddress("152.7.178.169:19091");

        JoinSeedResponse response = joinRoutingService.resolveSeed(request);

        assertThat(response.bootstrapSelf()).isTrue();
        assertThat(response.seedGrpcAddress()).isEqualTo("152.7.178.169:19091");
        assertThat(registryService.snapshot().nodes())
                .extracting(node -> node.nodeId() + ":" + node.grpcAddress())
                .containsExactly("node-1:152.7.178.169:19091");
    }

    @Test
    void shouldMarkFailedSeedDeadAndRefreshFromFallbackNode() {
        NodeRegistryService registryService = registry(tempDir.resolve("fallback-refresh.json"));
        registryService.upsertManualNode("node-1", "node-1:9090");
        registryService.upsertManualNode("node-2", "10.0.0.2:9092");

        StubClusterClient clusterClient = new StubClusterClient();
        clusterClient.membershipState = MembershipState.newBuilder()
                .setTopologyVersion(5)
                .addMembership(member("node-2", "10.0.0.2:9092", MemberStatusProto.ALIVE))
                .build();
        clusterClient.failAddress = "node-1:9090";

        JoinRoutingService joinRoutingService = new JoinRoutingService(registryService, clusterClient, properties());

        var refreshed = joinRoutingService.refreshFromCluster("node-1:9090");

        assertThat(registryService.snapshot().nodes())
                .extracting(node -> node.nodeId() + ":" + node.status())
                .containsExactly("node-2:ALIVE");
        assertThat(refreshed.nodes())
                .extracting(node -> node.nodeId() + ":" + node.grpcAddress())
                .containsExactly("node-2:10.0.0.2:9092");
    }

    @Test
    void shouldConfirmJoinFromLocalRegistryWhenClusterRefreshIsUnavailable() {
        NodeRegistryService registryService = registry(tempDir.resolve("confirm-unavailable.json"));
        registryService.upsertManualNode("node-1", "152.7.178.169:19091");
        registryService.upsertManualNode("node-2", "152.7.178.169:19092");

        StubClusterClient clusterClient = new StubClusterClient();
        clusterClient.failAddress = "152.7.178.169:19091";

        JoinRoutingService joinRoutingService = new JoinRoutingService(registryService, clusterClient, properties());

        JoinConfirmationResponse confirmation = joinRoutingService.confirmJoin(
                "node-2",
                "152.7.178.169:19091",
                1,
                1L
        );

        assertThat(confirmation.confirmed()).isTrue();
        assertThat(confirmation.registry().nodes())
                .extracting(node -> node.nodeId() + ":" + node.status())
                .contains("node-1:ALIVE", "node-2:ALIVE");
    }

    private NodeRegistryService registry(Path path) {
        NodeRegistryService service = new NodeRegistryService(
                new ObjectMapper().findAndRegisterModules(),
                properties(path),
                Clock.fixed(Instant.parse("2026-04-22T12:00:00Z"), ZoneOffset.UTC)
        );
        service.load();
        return service;
    }

    private ClientRouterProperties properties() {
        return properties(tempDir.resolve("default.json"));
    }

    private ClientRouterProperties properties(Path path) {
        ClientRouterProperties properties = new ClientRouterProperties();
        properties.setRegistryPath(path.toString());
        properties.setJoinConfirmationAttempts(2);
        properties.setJoinConfirmationDelayMs(1);
        return properties;
    }

    private static MemberRecordProto member(String nodeId, String address, MemberStatusProto status) {
        return MemberRecordProto.newBuilder()
                .setNodeId(nodeId)
                .setAddress(address)
                .setStatus(status)
                .build();
    }

    private static final class StubClusterClient implements OrionClusterClient {
        private MembershipState membershipState;
        private String failAddress;

        @Override
        public MembershipState getMembership(String grpcAddress) {
            if (grpcAddress != null && grpcAddress.equals(failAddress)) {
                throw new RuntimeException("UNAVAILABLE: Unable to resolve host");
            }
            return membershipState;
        }

        @Override
        public com.orionkv.proto.ClientPutResponse put(String grpcAddress, String requestId, String key, String value, long timestamp) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.orionkv.proto.ClientGetResponse get(String grpcAddress, String requestId, String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.orionkv.proto.ClientDeleteResponse delete(String grpcAddress, String requestId, String key, long timestamp) {
            throw new UnsupportedOperationException();
        }
    }
}
