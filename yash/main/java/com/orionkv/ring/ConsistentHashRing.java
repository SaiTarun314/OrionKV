package com.orionkv.ring;

import com.orionkv.model.NodeInfo;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

public final class ConsistentHashRing implements ReplicaSelector {
    private final HashFunction hashFunction;
    private final NavigableMap<Long, NodeInfo> vnodeMap = new TreeMap<>();

    public ConsistentHashRing(HashFunction hashFunction, List<NodeInfo> nodes, int virtualNodesPerPhysical) {
        this.hashFunction = Objects.requireNonNull(hashFunction, "hashFunction");
        buildRing(nodes, virtualNodesPerPhysical);
    }

    @Override
    public List<NodeInfo> selectReplicas(String key, int replicationFactor) {
        if (vnodeMap.isEmpty()) {
            throw new IllegalStateException("Hash ring is empty");
        }
        if (replicationFactor <= 0) {
            throw new IllegalArgumentException("replicationFactor must be > 0");
        }

        long token = hashFunction.hashToLong(key);
        LinkedHashSet<NodeInfo> owners = new LinkedHashSet<>();

        for (Map.Entry<Long, NodeInfo> entry : vnodeMap.tailMap(token, true).entrySet()) {
            owners.add(entry.getValue());
            if (owners.size() == replicationFactor) {
                return List.copyOf(owners);
            }
        }

        for (NodeInfo owner : vnodeMap.values()) {
            owners.add(owner);
            if (owners.size() == replicationFactor) {
                return List.copyOf(owners);
            }
        }

        return List.copyOf(owners);
    }

    private void buildRing(List<NodeInfo> nodes, int virtualNodesPerPhysical) {
        Objects.requireNonNull(nodes, "nodes");
        if (virtualNodesPerPhysical <= 0) {
            throw new IllegalArgumentException("virtualNodesPerPhysical must be > 0");
        }

        for (NodeInfo node : new ArrayList<>(nodes)) {
            for (int i = 0; i < virtualNodesPerPhysical; i++) {
                String vnodeId = node.nodeId() + "#" + i;
                vnodeMap.put(hashFunction.hashToLong(vnodeId), node);
            }
        }
    }
}
