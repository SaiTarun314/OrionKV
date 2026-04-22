package com.orionkv.clientrouter.dto;

public class JoinConfirmationRequest {

    private String joiningNodeId;
    private String seedGrpcAddress;
    private Integer pollAttempts;
    private Long pollDelayMs;

    public String getJoiningNodeId() {
        return joiningNodeId;
    }

    public void setJoiningNodeId(String joiningNodeId) {
        this.joiningNodeId = joiningNodeId;
    }

    public String getSeedGrpcAddress() {
        return seedGrpcAddress;
    }

    public void setSeedGrpcAddress(String seedGrpcAddress) {
        this.seedGrpcAddress = seedGrpcAddress;
    }

    public Integer getPollAttempts() {
        return pollAttempts;
    }

    public void setPollAttempts(Integer pollAttempts) {
        this.pollAttempts = pollAttempts;
    }

    public Long getPollDelayMs() {
        return pollDelayMs;
    }

    public void setPollDelayMs(Long pollDelayMs) {
        this.pollDelayMs = pollDelayMs;
    }
}
