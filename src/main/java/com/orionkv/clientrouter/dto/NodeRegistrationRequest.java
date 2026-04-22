package com.orionkv.clientrouter.dto;

import jakarta.validation.constraints.NotBlank;

public class NodeRegistrationRequest {

    @NotBlank
    private String nodeId;

    @NotBlank
    private String grpcAddress;

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public String getGrpcAddress() {
        return grpcAddress;
    }

    public void setGrpcAddress(String grpcAddress) {
        this.grpcAddress = grpcAddress;
    }
}
