package com.orionkv.clientrouter.dto;

public class RefreshNodesRequest {

    private String seedGrpcAddress;

    public String getSeedGrpcAddress() {
        return seedGrpcAddress;
    }

    public void setSeedGrpcAddress(String seedGrpcAddress) {
        this.seedGrpcAddress = seedGrpcAddress;
    }
}
