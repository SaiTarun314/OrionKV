package com.orionkv.coordinationplane.service;

import com.orionkv.coordinationplane.model.QuorumConfig;
import org.springframework.stereotype.Service;

@Service
public class QuorumService {

    public boolean writeQuorumSatisfied(int ackCount, QuorumConfig quorumConfig) {
        return ackCount >= quorumConfig.writeQuorum();
    }

    public boolean readQuorumSatisfied(int responseCount, QuorumConfig quorumConfig) {
        return responseCount >= quorumConfig.readQuorum();
    }

    public boolean strongReadWriteIntersection(QuorumConfig quorumConfig) {
        return quorumConfig.readQuorum() + quorumConfig.writeQuorum() > quorumConfig.replicationFactor();
    }
}
