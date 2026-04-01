package com.orionkv.model;

import java.util.Objects;

public record NodeInfo(String nodeId, String host, int port) {
    public NodeInfo {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(host, "host");
    }
}
