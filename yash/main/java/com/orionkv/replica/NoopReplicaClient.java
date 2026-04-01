package com.orionkv.replica;

import com.orionkv.model.NodeInfo;
import com.orionkv.model.VersionedValue;
import java.util.Optional;

public final class NoopReplicaClient implements ReplicaClient {
    @Override
    public boolean replicateWrite(NodeInfo target, String requestId, String coordinatorNodeId, String key, byte[] value, long timestampEpochMs) {
        return false;
    }

    @Override
    public Optional<VersionedValue> read(NodeInfo target, String requestId, String coordinatorNodeId, String key) {
        return Optional.empty();
    }
}
