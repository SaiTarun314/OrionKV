package com.orionkv.coordinationplane.service;

import com.orionkv.coordinationplane.model.ReplicaRoute;
import com.orionkv.controlplane.ring.model.ReplicaSet;
import com.orionkv.controlplane.ring.service.HashRingService;
import org.springframework.stereotype.Service;

@Service
public class ReplicaRoutingService {

    private final HashRingService hashRingService;

    public ReplicaRoutingService(HashRingService hashRingService) {
        this.hashRingService = hashRingService;
    }

    public ReplicaRoute routeForKey(String key) {
        ReplicaSet replicaSet = hashRingService.findReplicas(key);
        return new ReplicaRoute(key, replicaSet.token(), replicaSet.replicaNodeIds());
    }
}
