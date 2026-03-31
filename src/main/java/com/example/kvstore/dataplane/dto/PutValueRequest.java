package com.example.kvstore.dataplane.dto;

import jakarta.validation.constraints.NotNull;

public record PutValueRequest(
    @NotNull(message = "value is required")
    String value,

    @NotNull(message = "timestamp is required")
    Long timestamp
) {
}
