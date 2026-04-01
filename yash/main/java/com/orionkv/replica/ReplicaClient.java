package com.orionkv.replica;

import com.orionkv.model.NodeInfo;
import com.orionkv.model.VersionedValue;
import java.util.Optional;

public interface ReplicaClient {
    boolean replicateWrite(NodeInfo target, String requestId, String coordinatorNodeId, String key, byte[] value, long timestampEpochMs);

    Optional<VersionedValue> read(NodeInfo target, String requestId, String coordinatorNodeId, String key);
}
