package com.orionkv.coordinationplane.service;

import com.orionkv.config.NodeProperties;
import com.orionkv.coordinationplane.model.QuorumConfig;
import com.orionkv.coordinationplane.model.ReplicaReadResult;
import com.orionkv.coordinationplane.model.ReplicaRoute;
import com.orionkv.coordinationplane.rpc.ReplicaDataClient;
import com.orionkv.controlplane.membership.model.MemberStatus;
import com.orionkv.controlplane.membership.service.MembershipService;
import com.orionkv.dataplane.exception.KeyNotFoundException;
import com.orionkv.dataplane.model.ReplicaRecord;
import com.orionkv.dataplane.model.StoredValue;
import com.orionkv.dataplane.service.StorageService;
import com.orionkv.proto.ClientDeleteResponse;
import com.orionkv.proto.ClientGetResponse;
import com.orionkv.proto.ClientPutResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Service;

@Service
public class QuorumCoordinatorService {

    private final NodeProperties nodeProperties;
    private final ReplicaRoutingService replicaRoutingService;
    private final QuorumService quorumService;
    private final MembershipService membershipService;
    private final StorageService storageService;
    private final ReplicaDataClient replicaDataClient;
    private final ConcurrentMap<String, Long> requestTimestampById = new ConcurrentHashMap<>();

    public QuorumCoordinatorService(
            NodeProperties nodeProperties,
            ReplicaRoutingService replicaRoutingService,
            QuorumService quorumService,
            MembershipService membershipService,
            StorageService storageService,
            ReplicaDataClient replicaDataClient
    ) {
        this.nodeProperties = nodeProperties;
        this.replicaRoutingService = replicaRoutingService;
        this.quorumService = quorumService;
        this.membershipService = membershipService;
        this.storageService = storageService;
        this.replicaDataClient = replicaDataClient;
    }

    public ClientPutResponse put(String requestId, String key, String value, long timestamp) {
        long writeTimestamp = resolveWriteTimestamp(requestId, timestamp);
        WriteQuorumResult result = writeWithQuorum(requestId, key, value, writeTimestamp, false);
        return ClientPutResponse.newBuilder()
                .setSuccess(result.success())
                .setKey(key)
                .setToken(result.token())
                .setTimestamp(writeTimestamp)
                .setAckCount(result.ackCount())
                .setRequiredAcks(result.requiredAcks())
                .addAllReplicaNodeIds(result.replicaNodeIds())
                .addAllAcknowledgedNodeIds(result.acknowledgedNodeIds())
                .setMessage(writeQuorumMessage(
                        result.success(),
                        result.ackCount(),
                        result.requiredAcks(),
                        result.replicaNodeIds().size(),
                        quorumConfig().replicationFactor()
                ))
                .build();
    }

    public ClientDeleteResponse delete(String requestId, String key, long timestamp) {
        long deleteTimestamp = resolveWriteTimestamp(requestId, timestamp);
        WriteQuorumResult result = writeWithQuorum(requestId, key, null, deleteTimestamp, true);
        return ClientDeleteResponse.newBuilder()
                .setSuccess(result.success())
                .setKey(key)
                .setToken(result.token())
                .setTimestamp(deleteTimestamp)
                .setAckCount(result.ackCount())
                .setRequiredAcks(result.requiredAcks())
                .addAllReplicaNodeIds(result.replicaNodeIds())
                .addAllAcknowledgedNodeIds(result.acknowledgedNodeIds())
                .setMessage(deleteQuorumMessage(
                        result.success(),
                        result.ackCount(),
                        result.requiredAcks(),
                        result.replicaNodeIds().size(),
                        quorumConfig().replicationFactor()
                ))
                .build();
    }

    private WriteQuorumResult writeWithQuorum(String requestId, String key, String value, long timestamp, boolean tombstone) {
        ReplicaRoute route = replicaRoutingService.routeForKey(key);
        QuorumConfig quorumConfig = quorumConfig();
        List<String> activeReplicaNodeIds = activeReplicaNodeIds(route);

        int ackCount = 0;
        List<String> acknowledgedNodeIds = new ArrayList<>();
        for (String replicaNodeId : activeReplicaNodeIds) {
            if (isLocalNode(replicaNodeId)) {
                storageService.applyReplicaWrite(
                        new ReplicaRecord(
                                key,
                                value,
                                timestamp,
                                tombstone,
                                route.primaryToken(),
                                nodeProperties.getNodeId()
                        )
                );
                ackCount++;
                acknowledgedNodeIds.add(replicaNodeId);
                continue;
            }

            Optional<String> address = resolveReplicaAddress(replicaNodeId);
            if (address.isEmpty()) {
                continue;
            }

            boolean ack = replicaDataClient.putReplica(
                    address.get(),
                    requestId,
                    new ReplicaRecord(key, value, timestamp, tombstone, route.primaryToken(), nodeProperties.getNodeId())
            );
            if (ack) {
                ackCount++;
                acknowledgedNodeIds.add(replicaNodeId);
            }
        }

        boolean success = quorumService.writeQuorumSatisfied(ackCount, quorumConfig);
        return new WriteQuorumResult(
                success,
                route.primaryToken(),
                ackCount,
                quorumConfig.writeQuorum(),
                activeReplicaNodeIds,
                acknowledgedNodeIds
        );
    }

    public ClientGetResponse get(String requestId, String key) {
        ReplicaRoute route = replicaRoutingService.routeForKey(key);
        QuorumConfig quorumConfig = quorumConfig();
        List<String> activeReplicaNodeIds = activeReplicaNodeIds(route);

        List<ReplicaReadResult> readResults = new ArrayList<>();
        int responseCount = 0;
        List<String> respondedNodeIds = new ArrayList<>();

        for (String replicaNodeId : activeReplicaNodeIds) {
            if (isLocalNode(replicaNodeId)) {
                responseCount++;
                respondedNodeIds.add(replicaNodeId);
                try {
                    StoredValue value = storageService.get(key);
                    readResults.add(new ReplicaReadResult(
                            true,
                            true,
                            value.key(),
                            value.value(),
                            value.token(),
                            value.timestamp(),
                            value.tombstone(),
                            nodeProperties.getNodeId()
                    ));
                } catch (KeyNotFoundException ignored) {
                    readResults.add(new ReplicaReadResult(true, false, key, null, -1L, -1L, false, nodeProperties.getNodeId()));
                }
            } else {
                Optional<String> address = resolveReplicaAddress(replicaNodeId);
                if (address.isPresent()) {
                    ReplicaReadResult result = replicaDataClient.getReplica(address.get(), requestId, key);
                    if (result.responded()) {
                        responseCount++;
                        respondedNodeIds.add(replicaNodeId);
                        readResults.add(result);
                    }
                }
            }
        }

        if (!quorumService.readQuorumSatisfied(responseCount, quorumConfig)) {
            return ClientGetResponse.newBuilder()
                    .setFound(false)
                    .setKey(key)
                    .setResponseCount(responseCount)
                    .setRequiredResponses(quorumConfig.readQuorum())
                    .addAllReplicaNodeIds(activeReplicaNodeIds)
                    .addAllRespondedNodeIds(respondedNodeIds)
                    .setMessage(readQuorumMessage(false, responseCount, quorumConfig.readQuorum(),
                            activeReplicaNodeIds.size(), route.replicaNodeIds().size()))
                    .build();
        }

        Optional<ReplicaReadResult> winner = readResults.stream()
                .filter(ReplicaReadResult::found)
                .max(Comparator.comparingLong(ReplicaReadResult::timestamp)
                        .thenComparing(result -> result.nodeId() == null ? "" : result.nodeId()));

        if (winner.isEmpty()) {
            return ClientGetResponse.newBuilder()
                    .setFound(false)
                    .setKey(key)
                    .setResponseCount(responseCount)
                    .setRequiredResponses(quorumConfig.readQuorum())
                    .addAllReplicaNodeIds(activeReplicaNodeIds)
                    .addAllRespondedNodeIds(respondedNodeIds)
                    .setMessage(notFoundMessage(activeReplicaNodeIds.size(), route.replicaNodeIds().size()))
                    .build();
        }

        ReplicaReadResult latest = winner.get();
        triggerReadRepairAsync(requestId, key, latest, readResults);

        if (latest.tombstone()) {
            return ClientGetResponse.newBuilder()
                    .setFound(false)
                    .setKey(key)
                    .setResponseCount(responseCount)
                    .setRequiredResponses(quorumConfig.readQuorum())
                    .addAllReplicaNodeIds(activeReplicaNodeIds)
                    .addAllRespondedNodeIds(respondedNodeIds)
                    .setMessage(notFoundMessage(activeReplicaNodeIds.size(), route.replicaNodeIds().size()))
                    .build();
        }

        return ClientGetResponse.newBuilder()
                .setFound(true)
                .setKey(latest.key())
                .setValue(latest.value() == null ? "" : latest.value())
                .setToken(latest.token())
                .setTimestamp(latest.timestamp())
                .setTombstone(latest.tombstone())
                .setResponseCount(responseCount)
                .setRequiredResponses(quorumConfig.readQuorum())
                .addAllReplicaNodeIds(activeReplicaNodeIds)
                .addAllRespondedNodeIds(respondedNodeIds)
                .setMessage(readQuorumMessage(true, responseCount, quorumConfig.readQuorum(),
                        activeReplicaNodeIds.size(), route.replicaNodeIds().size()))
                .build();
    }

    private List<String> activeReplicaNodeIds(ReplicaRoute route) {
        return route.replicaNodeIds().stream()
                .filter(this::isEligibleReplica)
                .toList();
    }

    private boolean isEligibleReplica(String replicaNodeId) {
        if (isLocalNode(replicaNodeId)) {
            return true;
        }
        return membershipService.getMember(replicaNodeId)
                .map(member -> member.status() == MemberStatus.ALIVE)
                .orElse(false);
    }

    private Optional<String> resolveReplicaAddress(String replicaNodeId) {
        return membershipService.getMember(replicaNodeId)
                .filter(member -> member.status() == MemberStatus.ALIVE)
                .map(record -> record.address());
    }

    private String writeQuorumMessage(
            boolean success,
            int ackCount,
            int requiredAcks,
            int activeReplicaCount,
            int configuredReplicaCount
    ) {
        if (activeReplicaCount == configuredReplicaCount) {
            return success ? "write quorum satisfied" : "write quorum not met";
        }
        return success
                ? "write quorum satisfied with " + ackCount + "/" + activeReplicaCount
                + " active replicas (" + configuredReplicaCount + " configured)"
                : "write quorum not met: " + ackCount + "/" + activeReplicaCount
                + " active replicas (" + requiredAcks + " required, " + configuredReplicaCount + " configured)";
    }

    private String deleteQuorumMessage(
            boolean success,
            int ackCount,
            int requiredAcks,
            int activeReplicaCount,
            int configuredReplicaCount
    ) {
        if (activeReplicaCount == configuredReplicaCount) {
            return success ? "delete quorum satisfied" : "delete quorum not met";
        }
        return success
                ? "delete quorum satisfied with " + ackCount + "/" + activeReplicaCount
                + " active replicas (" + configuredReplicaCount + " configured)"
                : "delete quorum not met: " + ackCount + "/" + activeReplicaCount
                + " active replicas (" + requiredAcks + " required, " + configuredReplicaCount + " configured)";
    }

    private String readQuorumMessage(
            boolean success,
            int responseCount,
            int requiredResponses,
            int activeReplicaCount,
            int configuredReplicaCount
    ) {
        if (activeReplicaCount == configuredReplicaCount) {
            return success ? "read quorum satisfied" : "read quorum not met";
        }
        return success
                ? "read quorum satisfied with " + responseCount + "/" + activeReplicaCount
                + " active replicas (" + configuredReplicaCount + " configured)"
                : "read quorum not met: " + responseCount + "/" + activeReplicaCount
                + " active replicas (" + requiredResponses + " required, " + configuredReplicaCount + " configured)";
    }

    private String notFoundMessage(int activeReplicaCount, int configuredReplicaCount) {
        if (activeReplicaCount == configuredReplicaCount) {
            return "not found";
        }
        return "not found among " + activeReplicaCount + "/" + configuredReplicaCount + " active replicas";
    }

    private void triggerReadRepairAsync(String requestId, String key, ReplicaReadResult latest, List<ReplicaReadResult> readResults) {
        for (ReplicaReadResult replicaResult : readResults) {
            if (replicaResult.timestamp() >= latest.timestamp()) {
                continue;
            }

            CompletableFuture.runAsync(() -> repairReplica(requestId, key, latest, replicaResult.nodeId()))
                    .exceptionally(ex -> null);
        }
    }

    private void repairReplica(String requestId, String key, ReplicaReadResult latest, String replicaNodeId) {
        if (replicaNodeId == null || replicaNodeId.isBlank()) {
            return;
        }

        ReplicaRecord repairRecord = new ReplicaRecord(
                key,
                latest.value(),
                latest.timestamp(),
                latest.tombstone(),
                latest.token(),
                nodeProperties.getNodeId()
        );

        if (isLocalNode(replicaNodeId)) {
            try {
                storageService.applyReplicaWrite(repairRecord);
            } catch (RuntimeException ignored) {
                // best-effort repair
            }
            return;
        }

        try {
            resolveReplicaAddress(replicaNodeId).ifPresent(address -> replicaDataClient.putReplica(
                    address,
                    readRepairRequestId(requestId),
                    repairRecord
            ));
        } catch (RuntimeException ignored) {
            // best-effort repair
        }
    }

    private String readRepairRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return "read-repair";
        }
        return requestId + "-read-repair";
    }

    private long resolveWriteTimestamp(String requestId, long incomingTimestamp) {
        if (incomingTimestamp > 0 && !hasRequestId(requestId)) {
            return incomingTimestamp;
        }
        if (incomingTimestamp <= 0 && !hasRequestId(requestId)) {
            return System.currentTimeMillis();
        }

        return requestTimestampById.compute(requestId, (id, existingTimestamp) -> {
            if (existingTimestamp != null) {
                return existingTimestamp;
            }
            if (incomingTimestamp > 0) {
                return incomingTimestamp;
            }
            return System.currentTimeMillis();
        });
    }

    private boolean hasRequestId(String requestId) {
        return requestId != null && !requestId.isBlank();
    }

    private boolean isLocalNode(String nodeId) {
        return nodeId != null && nodeId.equals(nodeProperties.getNodeId());
    }

    private QuorumConfig quorumConfig() {
        return new QuorumConfig(
                nodeProperties.getReplicationFactor(),
                nodeProperties.getWriteQuorum(),
                nodeProperties.getReadQuorum()
        );
    }

    private record WriteQuorumResult(
            boolean success,
            long token,
            int ackCount,
            int requiredAcks,
            List<String> replicaNodeIds,
            List<String> acknowledgedNodeIds
    ) {
    }
}
