package com.orionkv.clientrouter.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orionkv.clientrouter.config.ClientRouterProperties;
import com.orionkv.clientrouter.model.NodeStatus;
import com.orionkv.clientrouter.model.RouterNodeRecord;
import com.orionkv.clientrouter.model.RouterRegistrySnapshot;
import com.orionkv.proto.MemberRecordProto;
import com.orionkv.proto.MembershipState;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class NodeRegistryService {

    private final ObjectMapper objectMapper;
    private final ClientRouterProperties properties;
    private final Clock clock;
    private final AtomicInteger nextIndex = new AtomicInteger();

    private long topologyVersion;
    private Instant updatedAt;
    private final Map<String, RouterNodeRecord> nodesById = new LinkedHashMap<>();

    public NodeRegistryService(ObjectMapper objectMapper, ClientRouterProperties properties) {
        this(objectMapper, properties, Clock.systemUTC());
    }

    NodeRegistryService(ObjectMapper objectMapper, ClientRouterProperties properties, Clock clock) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    @PostConstruct
    void load() {
        Path path = registryPath();
        if (!Files.exists(path)) {
            updatedAt = Instant.now(clock);
            return;
        }

        try {
            PersistedRegistry persisted = objectMapper.readValue(path.toFile(), PersistedRegistry.class);
            topologyVersion = persisted.topologyVersion;
            updatedAt = persisted.updatedAt == null ? Instant.now(clock) : persisted.updatedAt;
            nodesById.clear();
            if (persisted.nodes != null) {
                for (RouterNodeRecord node : persisted.nodes) {
                    nodesById.put(node.nodeId(), node);
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Could not load client router registry from " + path, ex);
        }
    }

    public synchronized RouterRegistrySnapshot snapshot() {
        return new RouterRegistrySnapshot(topologyVersion, updatedAt, orderedNodes());
    }

    public synchronized RouterNodeRecord upsertManualNode(String nodeId, String grpcAddress) {
        RouterNodeRecord node = new RouterNodeRecord(nodeId, normalizeAddress(grpcAddress), NodeStatus.ALIVE, Instant.now(clock));
        nodesById.put(nodeId, node);
        if (updatedAt == null) {
            updatedAt = Instant.now(clock);
        } else {
            updatedAt = Instant.now(clock);
        }
        persist();
        return node;
    }

    public synchronized RouterRegistrySnapshot replaceFromMembership(MembershipState membershipState) {
        Map<String, RouterNodeRecord> refreshed = new LinkedHashMap<>();
        Instant now = Instant.now(clock);
        for (MemberRecordProto member : membershipState.getMembershipList()) {
            refreshed.put(member.getNodeId(), new RouterNodeRecord(
                    member.getNodeId(),
                    normalizeAddress(member.getAddress()),
                    NodeStatus.valueOf(member.getStatus().name()),
                    now
            ));
        }
        nodesById.clear();
        nodesById.putAll(refreshed);
        topologyVersion = membershipState.getTopologyVersion();
        updatedAt = now;
        persist();
        return snapshot();
    }

    public synchronized Optional<RouterNodeRecord> pickAliveNode() {
        List<RouterNodeRecord> aliveNodes = aliveNodes();
        if (aliveNodes.isEmpty()) {
            return Optional.empty();
        }
        int index = Math.floorMod(nextIndex.getAndIncrement(), aliveNodes.size());
        return Optional.of(aliveNodes.get(index));
    }

    public synchronized List<RouterNodeRecord> aliveNodes() {
        return orderedNodes().stream()
                .filter(RouterNodeRecord::isAlive)
                .toList();
    }

    public synchronized boolean hasAliveNodes() {
        return nodesById.values().stream().anyMatch(RouterNodeRecord::isAlive);
    }

    private List<RouterNodeRecord> orderedNodes() {
        return nodesById.values().stream()
                .sorted(Comparator.comparing(RouterNodeRecord::nodeId))
                .toList();
    }

    private void persist() {
        Path path = registryPath();
        try {
            Files.createDirectories(path.getParent());
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(path.toFile(), new PersistedRegistry(topologyVersion, updatedAt, new ArrayList<>(orderedNodes())));
        } catch (IOException ex) {
            throw new IllegalStateException("Could not persist client router registry to " + path, ex);
        }
    }

    private Path registryPath() {
        return Path.of(properties.getRegistryPath()).toAbsolutePath();
    }

    private String normalizeAddress(String grpcAddress) {
        return grpcAddress == null ? null : grpcAddress.replaceFirst("^https?://", "");
    }

    private static final class PersistedRegistry {
        public long topologyVersion;
        public Instant updatedAt;
        public List<RouterNodeRecord> nodes;

        public PersistedRegistry() {
        }

        private PersistedRegistry(long topologyVersion, Instant updatedAt, List<RouterNodeRecord> nodes) {
            this.topologyVersion = topologyVersion;
            this.updatedAt = updatedAt;
            this.nodes = nodes;
        }
    }
}
