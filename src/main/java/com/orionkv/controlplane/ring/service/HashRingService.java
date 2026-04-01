package com.orionkv.controlplane.ring.service;

import com.orionkv.common.util.HashUtil;
import com.orionkv.config.NodeProperties;
import com.orionkv.controlplane.membership.model.MemberRecord;
import com.orionkv.controlplane.membership.model.MemberStatus;
import com.orionkv.controlplane.ring.model.ReplicaSet;
import com.orionkv.controlplane.ring.model.TokenOwnership;
import com.orionkv.controlplane.ring.model.TokenRange;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

@Service
public class HashRingService {

    private final TreeMap<Long, String> ring = new TreeMap<>();
    private final VirtualNodeService virtualNodeService;
    private final NodeProperties nodeProperties;

    public HashRingService(VirtualNodeService virtualNodeService, NodeProperties nodeProperties) {
        this.virtualNodeService = virtualNodeService;
        this.nodeProperties = nodeProperties;
    }

    public synchronized void rebuildRing(Collection<MemberRecord> members) {
        ring.clear();
        if (members == null || members.isEmpty()) {
            return;
        }

        members.stream()
                .filter(member -> member.status() == MemberStatus.ALIVE)
                .forEach(member -> ring.putAll(
                        virtualNodeService.generateTokens(member.nodeId(), nodeProperties.getVirtualNodeCount())
                ));
    }

    public synchronized ReplicaSet findReplicas(String key) {
        if (ring.isEmpty()) {
            return new ReplicaSet(-1L, List.of());
        }

        long keyToken = HashUtil.hash(key);
        long primaryToken = ring.ceilingKey(keyToken) != null ? ring.ceilingKey(keyToken) : ring.firstKey();
        LinkedHashSet<String> replicas = new LinkedHashSet<>();

        collectReplicas(ring.tailMap(primaryToken, true), replicas);
        if (replicas.size() < nodeProperties.getReplicationFactor()) {
            collectReplicas(ring.headMap(primaryToken, false), replicas);
        }

        return new ReplicaSet(primaryToken, new ArrayList<>(replicas));
    }

    public synchronized List<TokenOwnership> getTokenOwnerships() {
        return ring.entrySet().stream()
                .map(entry -> new TokenOwnership(entry.getKey(), entry.getValue()))
                .toList();
    }

    public synchronized List<TokenRange> getOwnedTokenRanges(String nodeId) {
        if (ring.isEmpty()) {
            return List.of();
        }

        List<Map.Entry<Long, String>> entries = new ArrayList<>(ring.entrySet());
        List<TokenRange> ranges = new ArrayList<>();

        for (int i = 0; i < entries.size(); i++) {
            Map.Entry<Long, String> current = entries.get(i);
            Map.Entry<Long, String> previous = i == 0 ? entries.get(entries.size() - 1) : entries.get(i - 1);
            if (current.getValue().equals(nodeId)) {
                ranges.add(new TokenRange(previous.getKey(), current.getKey(), nodeId));
            }
        }

        return ranges;
    }

    private void collectReplicas(NavigableMap<Long, String> tokenMap, LinkedHashSet<String> replicas) {
        for (String nodeId : tokenMap.values()) {
            replicas.add(nodeId);
            if (replicas.size() >= nodeProperties.getReplicationFactor()) {
                return;
            }
        }
    }
}
