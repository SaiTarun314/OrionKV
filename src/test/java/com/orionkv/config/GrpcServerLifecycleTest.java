package com.orionkv.config;

import com.orionkv.clientrouter.dto.JoinSeedResponse;
import com.orionkv.controlplane.bootstrap.service.ClientRouterSeedClient;
import com.orionkv.controlplane.membership.service.MembershipService;
import com.orionkv.controlplane.ring.service.HashRingService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GrpcServerLifecycleTest {

    @Test
    void resolvesSeedFromClientRouterWhenAvailable() {
        NodeProperties nodeProperties = new NodeProperties();
        nodeProperties.setNodeId("node-2");
        nodeProperties.setAddress("127.0.0.1:9092");
        nodeProperties.setSeedAddress("127.0.0.1:9091");

        GrpcServerLifecycle lifecycle = new GrpcServerLifecycle(
                nodeProperties,
                new MembershipService(Clock.fixed(Instant.parse("2026-04-22T00:00:00Z"), ZoneOffset.UTC)),
                new HashRingService(null, nodeProperties),
                new StubClientRouterSeedClient(new JoinSeedResponse(false, "node-1", "10.0.0.1:9090", 1, 1L)),
                null,
                null,
                null,
                null,
                null,
                null
        );

        assertThat(lifecycle.resolveSeedAddressForStartup()).isEqualTo("10.0.0.1:9090");
    }

    @Test
    void returnsNullWhenClientRouterBootstrapsSelf() {
        NodeProperties nodeProperties = new NodeProperties();
        nodeProperties.setNodeId("node-1");
        nodeProperties.setAddress("127.0.0.1:9091");

        GrpcServerLifecycle lifecycle = new GrpcServerLifecycle(
                nodeProperties,
                new MembershipService(Clock.fixed(Instant.parse("2026-04-22T00:00:00Z"), ZoneOffset.UTC)),
                new HashRingService(null, nodeProperties),
                new StubClientRouterSeedClient(new JoinSeedResponse(true, "node-1", "127.0.0.1:9091", 1, 1L)),
                null,
                null,
                null,
                null,
                null,
                null
        );

        assertThat(lifecycle.resolveSeedAddressForStartup()).isNull();
    }

    @Test
    void fallsBackToStaticSeedWhenClientRouterReturnsEmpty() {
        NodeProperties nodeProperties = new NodeProperties();
        nodeProperties.setNodeId("node-3");
        nodeProperties.setAddress("127.0.0.1:9093");
        nodeProperties.setSeedAddress("127.0.0.1:9091");

        GrpcServerLifecycle lifecycle = new GrpcServerLifecycle(
                nodeProperties,
                new MembershipService(Clock.fixed(Instant.parse("2026-04-22T00:00:00Z"), ZoneOffset.UTC)),
                new HashRingService(null, nodeProperties),
                new StubClientRouterSeedClient(null),
                null,
                null,
                null,
                null,
                null,
                null
        );

        assertThat(lifecycle.resolveSeedAddressForStartup()).isEqualTo("127.0.0.1:9091");
    }

    private static final class StubClientRouterSeedClient extends ClientRouterSeedClient {

        private final JoinSeedResponse response;

        private StubClientRouterSeedClient(JoinSeedResponse response) {
            super((org.springframework.web.client.RestClient) null, new NodeProperties());
            this.response = response;
        }

        @Override
        public Optional<JoinSeedResponse> resolveJoinSeed() {
            return Optional.ofNullable(response);
        }
    }
}
