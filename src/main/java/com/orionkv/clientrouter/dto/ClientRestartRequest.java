package com.orionkv.clientrouter.dto;

public class ClientRestartRequest {

    private boolean clearRegistry;
    private long delayMs = 250;

    public boolean isClearRegistry() {
        return clearRegistry;
    }

    public void setClearRegistry(boolean clearRegistry) {
        this.clearRegistry = clearRegistry;
    }

    public long getDelayMs() {
        return delayMs;
    }

    public void setDelayMs(long delayMs) {
        this.delayMs = delayMs;
    }
}
