package com.orionkv.clientrouter.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orionkv.clientrouter.config.ClientRouterProperties;
import com.orionkv.clientrouter.model.NodeStatus;
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

class NodeRegistryServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldPersistAndReloadNodes() {
        ClientRouterProperties properties = new ClientRouterProperties();
        properties.setRegistryPath(tempDir.resolve("registry.json").toString());

        NodeRegistryService registryService = new NodeRegistryService(
                new ObjectMapper().findAndRegisterModules(),
                properties,
                Clock.fixed(Instant.parse("2026-04-22T10:00:00Z"), ZoneOffset.UTC)
        );
        registryService.load();
        registryService.upsertManualNode("node-1", "127.0.0.1:9091");

        NodeRegistryService reloaded = new NodeRegistryService(
                new ObjectMapper().findAndRegisterModules(),
                properties,
                Clock.fixed(Instant.parse("2026-04-22T10:00:05Z"), ZoneOffset.UTC)
        );
        reloaded.load();

        assertThat(reloaded.snapshot().nodes())
                .hasSize(1)
                .first()
                .satisfies(node -> {
                    assertThat(node.nodeId()).isEqualTo("node-1");
                    assertThat(node.grpcAddress()).isEqualTo("127.0.0.1:9091");
                    assertThat(node.status()).isEqualTo(NodeStatus.ALIVE);
                });
    }

    @Test
    void shouldReplaceRegistryFromMembershipState() {
        ClientRouterProperties properties = new ClientRouterProperties();
        properties.setRegistryPath(tempDir.resolve("membership.json").toString());

        NodeRegistryService registryService = new NodeRegistryService(
                new ObjectMapper().findAndRegisterModules(),
                properties,
                Clock.fixed(Instant.parse("2026-04-22T10:00:00Z"), ZoneOffset.UTC)
        );
        registryService.load();

        MembershipState membershipState = MembershipState.newBuilder()
                .setTopologyVersion(7)
                .addMembership(MemberRecordProto.newBuilder()
                        .setNodeId("node-1")
                        .setAddress("127.0.0.1:9091")
                        .setStatus(MemberStatusProto.ALIVE)
                        .build())
                .addMembership(MemberRecordProto.newBuilder()
                        .setNodeId("node-2")
                        .setAddress("127.0.0.1:9092")
                        .setStatus(MemberStatusProto.SUSPECT)
                        .build())
                .build();

        registryService.replaceFromMembership(membershipState);

        assertThat(registryService.snapshot().topologyVersion()).isEqualTo(7);
        assertThat(registryService.snapshot().nodes())
                .extracting(node -> node.nodeId() + ":" + node.status())
                .containsExactly("node-1:ALIVE", "node-2:SUSPECT");
    }
}
