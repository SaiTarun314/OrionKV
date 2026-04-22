package com.orionkv.clientrouter.dto;

import jakarta.validation.constraints.NotBlank;

public class PutValueRequest {

    @NotBlank
    private String value;

    private Long timestamp;

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public Long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Long timestamp) {
        this.timestamp = timestamp;
    }
}
