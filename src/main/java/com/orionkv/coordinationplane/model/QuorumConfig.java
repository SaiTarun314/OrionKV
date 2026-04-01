package com.orionkv.coordinationplane.model;

public record QuorumConfig(
        int replicationFactor,
        int writeQuorum,
        int readQuorum
) {
    public QuorumConfig {
        if (replicationFactor <= 0) {
            throw new IllegalArgumentException("replicationFactor must be > 0");
        }
        if (writeQuorum <= 0 || writeQuorum > replicationFactor) {
            throw new IllegalArgumentException("writeQuorum must be in range [1, replicationFactor]");
        }
        if (readQuorum <= 0 || readQuorum > replicationFactor) {
            throw new IllegalArgumentException("readQuorum must be in range [1, replicationFactor]");
        }
    }
}
