package com.orionkv.ring;

import com.orionkv.model.NodeInfo;
import java.util.List;

public interface ReplicaSelector {
    List<NodeInfo> selectReplicas(String key, int replicationFactor);
}
