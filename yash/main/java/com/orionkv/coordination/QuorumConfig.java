package com.orionkv.coordination;

public record QuorumConfig(int n, int r, int w) {
    public QuorumConfig {
        if (n <= 0 || r <= 0 || w <= 0) {
            throw new IllegalArgumentException("n/r/w must be > 0");
        }
        if (r + w <= n) {
            throw new IllegalArgumentException("Invalid quorum. Must satisfy r + w > n");
        }
    }
}
