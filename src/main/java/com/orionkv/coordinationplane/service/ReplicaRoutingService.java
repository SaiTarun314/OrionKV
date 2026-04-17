package com.orionkv.coordinationplane.service;

import com.orionkv.coordinationplane.model.ReplicaRoute;
import com.orionkv.controlplane.membership.service.MembershipService;
import com.orionkv.controlplane.ring.model.ReplicaSet;
import com.orionkv.controlplane.ring.service.HashRingService;
import org.springframework.stereotype.Service;

@Service
public class ReplicaRoutingService {

    private final HashRingService hashRingService;
    private final MembershipService membershipService;

    public ReplicaRoutingService(HashRingService hashRingService, MembershipService membershipService) {
        this.hashRingService = hashRingService;
        this.membershipService = membershipService;
    }

    public ReplicaRoute routeForKey(String key) {
        hashRingService.rebuildRing(membershipService.getMembershipSnapshot());
        ReplicaSet replicaSet = hashRingService.findReplicas(key);
        return new ReplicaRoute(key, replicaSet.token(), replicaSet.replicaNodeIds());
    }
}
