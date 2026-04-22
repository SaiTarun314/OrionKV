package com.orionkv.clientrouter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "client.router")
public class ClientRouterProperties {

    private String registryPath = "data/client-router-nodes.json";
    private long refreshIntervalMs = 5000;
    private long rpcTimeoutMs = 3000;
    private int joinConfirmationAttempts = 20;
    private long joinConfirmationDelayMs = 1000;

    public String getRegistryPath() {
        return registryPath;
    }

    public void setRegistryPath(String registryPath) {
        this.registryPath = registryPath;
    }

    public long getRefreshIntervalMs() {
        return refreshIntervalMs;
    }

    public void setRefreshIntervalMs(long refreshIntervalMs) {
        this.refreshIntervalMs = refreshIntervalMs;
    }

    public long getRpcTimeoutMs() {
        return rpcTimeoutMs;
    }

    public void setRpcTimeoutMs(long rpcTimeoutMs) {
        this.rpcTimeoutMs = rpcTimeoutMs;
    }

    public int getJoinConfirmationAttempts() {
        return joinConfirmationAttempts;
    }

    public void setJoinConfirmationAttempts(int joinConfirmationAttempts) {
        this.joinConfirmationAttempts = joinConfirmationAttempts;
    }

    public long getJoinConfirmationDelayMs() {
        return joinConfirmationDelayMs;
    }

    public void setJoinConfirmationDelayMs(long joinConfirmationDelayMs) {
        this.joinConfirmationDelayMs = joinConfirmationDelayMs;
    }
}
