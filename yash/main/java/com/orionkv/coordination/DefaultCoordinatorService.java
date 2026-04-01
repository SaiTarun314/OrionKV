package com.orionkv.coordination;

import com.orionkv.model.NodeInfo;
import com.orionkv.model.VersionedValue;
import com.orionkv.replica.ReplicaClient;
import com.orionkv.ring.ReplicaSelector;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class DefaultCoordinatorService implements CoordinatorService {
    private final String localNodeId;
    private final QuorumConfig quorum;
    private final ReplicaSelector replicaSelector;
    private final ReplicaClient replicaClient;
    private final Clock clock;

    public DefaultCoordinatorService(
            String localNodeId,
            QuorumConfig quorum,
            ReplicaSelector replicaSelector,
            ReplicaClient replicaClient,
            Clock clock) {
        this.localNodeId = Objects.requireNonNull(localNodeId, "localNodeId");
        this.quorum = Objects.requireNonNull(quorum, "quorum");
        this.replicaSelector = Objects.requireNonNull(replicaSelector, "replicaSelector");
        this.replicaClient = Objects.requireNonNull(replicaClient, "replicaClient");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public int handlePut(String requestId, String key, byte[] value) {
        List<NodeInfo> replicas = replicaSelector.selectReplicas(key, quorum.n());
        long timestamp = clock.millis();

        int ackCount = 0;
        for (NodeInfo replica : replicas) {
            boolean ack = replicaClient.replicateWrite(replica, requestId, localNodeId, key, value, timestamp);
            if (ack) {
                ackCount++;
            }
            if (ackCount >= quorum.w()) {
                return ackCount;
            }
        }

        return ackCount;
    }

    @Override
    public Optional<VersionedValue> handleGet(String requestId, String key) {
        List<NodeInfo> replicas = replicaSelector.selectReplicas(key, quorum.n());
        List<VersionedValue> responses = new ArrayList<>();

        for (NodeInfo replica : replicas) {
            Optional<VersionedValue> value = replicaClient.read(replica, requestId, localNodeId, key);
            value.ifPresent(responses::add);
            if (responses.size() >= quorum.r()) {
                return responses.stream().max(lwwComparator());
            }
        }

        return Optional.empty();
    }

    private Comparator<VersionedValue> lwwComparator() {
        return Comparator
                .comparingLong(VersionedValue::timestampEpochMs)
                .thenComparing(VersionedValue::coordinatorNodeId);
    }
}
