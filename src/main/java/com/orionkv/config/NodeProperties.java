package com.orionkv.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "node")
public class NodeProperties {

    private String nodeId;
    private String address;
    private String seedAddress;
    private long gossipIntervalMs = 5000;
    private long selfHeartbeatIntervalMs = 1000;
    private long failureDetectionIntervalMs = 2000;
    private long suspectTimeoutMs = 10000;
    private long deadTimeoutMs = 30000;
    private int virtualNodeCount = 32;
    private int replicationFactor = 3;

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getSeedAddress() {
        return seedAddress;
    }

    public void setSeedAddress(String seedAddress) {
        this.seedAddress = seedAddress;
    }

    public long getGossipIntervalMs() {
        return gossipIntervalMs;
    }

    public void setGossipIntervalMs(long gossipIntervalMs) {
        this.gossipIntervalMs = gossipIntervalMs;
    }

    public long getFailureDetectionIntervalMs() {
        return failureDetectionIntervalMs;
    }

    public long getSelfHeartbeatIntervalMs() {
        return selfHeartbeatIntervalMs;
    }

    public void setSelfHeartbeatIntervalMs(long selfHeartbeatIntervalMs) {
        this.selfHeartbeatIntervalMs = selfHeartbeatIntervalMs;
    }

    public void setFailureDetectionIntervalMs(long failureDetectionIntervalMs) {
        this.failureDetectionIntervalMs = failureDetectionIntervalMs;
    }

    public long getSuspectTimeoutMs() {
        return suspectTimeoutMs;
    }

    public void setSuspectTimeoutMs(long suspectTimeoutMs) {
        this.suspectTimeoutMs = suspectTimeoutMs;
    }

    public long getDeadTimeoutMs() {
        return deadTimeoutMs;
    }

    public void setDeadTimeoutMs(long deadTimeoutMs) {
        this.deadTimeoutMs = deadTimeoutMs;
    }

    public int getVirtualNodeCount() {
        return virtualNodeCount;
    }

    public void setVirtualNodeCount(int virtualNodeCount) {
        this.virtualNodeCount = virtualNodeCount;
    }

    public int getReplicationFactor() {
        return replicationFactor;
    }

    public void setReplicationFactor(int replicationFactor) {
        this.replicationFactor = replicationFactor;
    }

    public int getPort() {
        String normalized = normalizeTarget(address);
        int separator = normalized.lastIndexOf(':');
        if (separator < 0 || separator == normalized.length() - 1) {
            throw new IllegalArgumentException("node.address must be host:port");
        }
        return Integer.parseInt(normalized.substring(separator + 1));
    }

    public static NodeProperties fromArgs(String[] args) {
        NodeProperties properties = new NodeProperties();
        for (String arg : args) {
            if (!arg.startsWith("--") || !arg.contains("=")) {
                continue;
            }

            String[] tokens = arg.substring(2).split("=", 2);
            String key = tokens[0];
            String value = tokens[1];

            switch (key) {
                case "node.node-id" -> properties.setNodeId(value);
                case "node.address" -> properties.setAddress(value);
                case "node.seed-address" -> properties.setSeedAddress(value);
                case "node.gossip-interval-ms" -> properties.setGossipIntervalMs(Long.parseLong(value));
                case "node.self-heartbeat-interval-ms" -> properties.setSelfHeartbeatIntervalMs(Long.parseLong(value));
                case "node.failure-detection-interval-ms" ->
                        properties.setFailureDetectionIntervalMs(Long.parseLong(value));
                case "node.suspect-timeout-ms" -> properties.setSuspectTimeoutMs(Long.parseLong(value));
                case "node.dead-timeout-ms" -> properties.setDeadTimeoutMs(Long.parseLong(value));
                case "node.virtual-node-count" -> properties.setVirtualNodeCount(Integer.parseInt(value));
                case "node.replication-factor" -> properties.setReplicationFactor(Integer.parseInt(value));
                default -> {
                }
            }
        }
        return properties;
    }

    private String normalizeTarget(String target) {
        if (target == null) {
            throw new IllegalArgumentException("node.address must be set");
        }
        return target.replaceFirst("^https?://", "");
    }
}
