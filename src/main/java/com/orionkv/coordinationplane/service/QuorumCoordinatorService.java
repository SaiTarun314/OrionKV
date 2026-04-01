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
import com.orionkv.proto.ClientGetResponse;
import com.orionkv.proto.ClientPutResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class QuorumCoordinatorService {

    private final NodeProperties nodeProperties;
    private final ReplicaRoutingService replicaRoutingService;
    private final QuorumService quorumService;
    private final MembershipService membershipService;
    private final StorageService storageService;
    private final ReplicaDataClient replicaDataClient;

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
        long writeTimestamp = timestamp > 0 ? timestamp : System.currentTimeMillis();
        ReplicaRoute route = replicaRoutingService.routeForKey(key);
        QuorumConfig quorumConfig = quorumConfig();

        int ackCount = 0;
        for (String replicaNodeId : route.replicaNodeIds()) {
            if (isLocalNode(replicaNodeId)) {
                storageService.put(key, value, writeTimestamp);
                ackCount++;
                continue;
            }

            Optional<String> address = resolveReplicaAddress(replicaNodeId);
            if (address.isEmpty()) {
                continue;
            }

            boolean ack = replicaDataClient.putReplica(
                    address.get(),
                    requestId,
                    new ReplicaRecord(key, value, writeTimestamp, false, route.primaryToken(), nodeProperties.getNodeId())
            );
            if (ack) {
                ackCount++;
            }
        }

        boolean success = quorumService.writeQuorumSatisfied(ackCount, quorumConfig);
        return ClientPutResponse.newBuilder()
                .setSuccess(success)
                .setKey(key)
                .setToken(route.primaryToken())
                .setTimestamp(writeTimestamp)
                .setAckCount(ackCount)
                .setRequiredAcks(quorumConfig.writeQuorum())
                .addAllReplicaNodeIds(route.replicaNodeIds())
                .setMessage(success ? "write quorum satisfied" : "write quorum not met")
                .build();
    }

    public ClientGetResponse get(String requestId, String key) {
        ReplicaRoute route = replicaRoutingService.routeForKey(key);
        QuorumConfig quorumConfig = quorumConfig();

        List<ReplicaReadResult> readResults = new ArrayList<>();
        int responseCount = 0;

        for (String replicaNodeId : route.replicaNodeIds()) {
            if (isLocalNode(replicaNodeId)) {
                responseCount++;
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
                        readResults.add(result);
                    }
                }
            }

            if (responseCount >= quorumConfig.readQuorum()) {
                break;
            }
        }

        if (!quorumService.readQuorumSatisfied(responseCount, quorumConfig)) {
            return ClientGetResponse.newBuilder()
                    .setFound(false)
                    .setKey(key)
                    .setResponseCount(responseCount)
                    .setRequiredResponses(quorumConfig.readQuorum())
                    .addAllReplicaNodeIds(route.replicaNodeIds())
                    .setMessage("read quorum not met")
                    .build();
        }

        Optional<ReplicaReadResult> winner = readResults.stream()
                .filter(ReplicaReadResult::found)
                .max(Comparator.comparingLong(ReplicaReadResult::timestamp)
                        .thenComparing(result -> result.nodeId() == null ? "" : result.nodeId()));

        if (winner.isEmpty() || winner.get().tombstone()) {
            return ClientGetResponse.newBuilder()
                    .setFound(false)
                    .setKey(key)
                    .setResponseCount(responseCount)
                    .setRequiredResponses(quorumConfig.readQuorum())
                    .addAllReplicaNodeIds(route.replicaNodeIds())
                    .setMessage("not found")
                    .build();
        }

        ReplicaReadResult value = winner.get();
        return ClientGetResponse.newBuilder()
                .setFound(true)
                .setKey(value.key())
                .setValue(value.value() == null ? "" : value.value())
                .setToken(value.token())
                .setTimestamp(value.timestamp())
                .setTombstone(value.tombstone())
                .setResponseCount(responseCount)
                .setRequiredResponses(quorumConfig.readQuorum())
                .addAllReplicaNodeIds(route.replicaNodeIds())
                .setMessage("read quorum satisfied")
                .build();
    }

    private Optional<String> resolveReplicaAddress(String replicaNodeId) {
        return membershipService.getMember(replicaNodeId)
                .filter(member -> member.status() == MemberStatus.ALIVE)
                .map(record -> record.address());
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
}
