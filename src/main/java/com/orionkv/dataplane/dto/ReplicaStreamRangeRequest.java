package com.orionkv.dataplane.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ReplicaStreamRangeRequest(
        @JsonAlias("start_token")
        @NotNull(message = "start_token is required")
        Long startToken,

        @JsonAlias("end_token")
        @NotNull(message = "end_token is required")
        Long endToken,

        @JsonAlias("batch_size")
        @Min(value = 1, message = "batch_size must be at least 1")
        Integer batchSize,

        String cursor
) {
    public int resolvedBatchSize() {
        return batchSize != null ? batchSize : 100;
    }
}
