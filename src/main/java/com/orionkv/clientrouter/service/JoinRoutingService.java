package com.orionkv.clientrouter.service;

import com.orionkv.clientrouter.config.ClientRouterProperties;
import com.orionkv.clientrouter.dto.JoinConfirmationResponse;
import com.orionkv.clientrouter.dto.JoinSeedResponse;
import com.orionkv.clientrouter.dto.NodeRegistrationRequest;
import com.orionkv.clientrouter.model.RouterNodeRecord;
import com.orionkv.clientrouter.model.RouterRegistrySnapshot;
import com.orionkv.proto.MembershipState;
import org.springframework.stereotype.Service;

@Service
public class JoinRoutingService {

    private final NodeRegistryService nodeRegistryService;
    private final OrionClusterClient clusterClient;
    private final ClientRouterProperties properties;

    public JoinRoutingService(
            NodeRegistryService nodeRegistryService,
            OrionClusterClient clusterClient,
            ClientRouterProperties properties
    ) {
        this.nodeRegistryService = nodeRegistryService;
        this.clusterClient = clusterClient;
        this.properties = properties;
    }

    public JoinSeedResponse resolveSeed(NodeRegistrationRequest request) {
        RouterNodeRecord self = nodeRegistryService.upsertManualNode(request.getNodeId(), request.getGrpcAddress());
        RouterRegistrySnapshot snapshot = nodeRegistryService.snapshot();
        if (nodeRegistryService.aliveNodes().size() == 1) {
            return new JoinSeedResponse(true, self.nodeId(), self.grpcAddress(), 1, snapshot.topologyVersion());
        }

        RouterNodeRecord seed = nodeRegistryService.aliveNodes().stream()
                .filter(node -> !node.nodeId().equals(request.getNodeId()))
                .findFirst()
                .orElse(self);
        return new JoinSeedResponse(false, seed.nodeId(), seed.grpcAddress(),
                nodeRegistryService.aliveNodes().size(), snapshot.topologyVersion());
    }

    public RouterRegistrySnapshot refreshFromCluster(String preferredSeedGrpcAddress) {
        String seedAddress = preferredSeedGrpcAddress;
        if (seedAddress == null || seedAddress.isBlank()) {
            seedAddress = nodeRegistryService.pickAliveNode()
                    .map(RouterNodeRecord::grpcAddress)
                    .orElseThrow(() -> new IllegalStateException("No seed node available to refresh membership"));
        }
        final String attemptedSeedAddress = seedAddress;
        try {
            MembershipState membership = clusterClient.getMembership(attemptedSeedAddress);
            if (membership == null) {
                throw new IllegalStateException("No membership returned from " + attemptedSeedAddress);
            }
            return nodeRegistryService.replaceFromMembership(membership);
        } catch (RuntimeException ex) {
            RouterNodeRecord attemptedSeedNode = nodeRegistryService.aliveNodes().stream()
                    .filter(node -> attemptedSeedAddress.equals(node.grpcAddress()))
                    .findFirst()
                    .orElse(null);

            String fallbackSeed = nodeRegistryService.aliveNodes().stream()
                    .map(RouterNodeRecord::grpcAddress)
                    .filter(address -> !address.equals(attemptedSeedAddress))
                    .findFirst()
                    .orElse(null);
            if (fallbackSeed != null && !fallbackSeed.isBlank()) {
                MembershipState membership = clusterClient.getMembership(fallbackSeed);
                if (membership == null) {
                    throw ex;
                }
                if (attemptedSeedNode != null) {
                    nodeRegistryService.markNodeStatus(
                            attemptedSeedNode.nodeId(),
                            com.orionkv.clientrouter.model.NodeStatus.DEAD
                    );
                }
                return nodeRegistryService.replaceFromMembership(membership);
            }
            throw ex;
        }
    }

    public RouterRegistrySnapshot refreshFromClusterBestEffort(String preferredSeedGrpcAddress) {
        try {
            return refreshFromCluster(preferredSeedGrpcAddress);
        } catch (RuntimeException ex) {
            return nodeRegistryService.snapshot();
        }
    }

    public JoinConfirmationResponse confirmJoin(String joiningNodeId, String seedGrpcAddress, Integer attempts, Long delayMs) {
        int maxAttempts = attempts == null || attempts <= 0
                ? properties.getJoinConfirmationAttempts()
                : attempts;
        long waitMs = delayMs == null || delayMs <= 0
                ? properties.getJoinConfirmationDelayMs()
                : delayMs;

        RouterRegistrySnapshot lastSnapshot = null;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            lastSnapshot = refreshFromClusterBestEffort(seedGrpcAddress);
            boolean confirmed = lastSnapshot.nodes().stream()
                    .anyMatch(node -> node.nodeId().equals(joiningNodeId) && node.isAlive());
            if (confirmed) {
                return new JoinConfirmationResponse(true, joiningNodeId, lastSnapshot);
            }

            if (attempt + 1 < maxAttempts) {
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        return new JoinConfirmationResponse(false, joiningNodeId,
                lastSnapshot == null ? nodeRegistryService.snapshot() : lastSnapshot);
    }
}
