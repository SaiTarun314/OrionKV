package com.example.kvstore.dataplane.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record ReplicaBatchApplyRequest(
    @NotNull(message = "records are required")
    @NotEmpty(message = "records must not be empty")
    List<@Valid ReplicaWriteRequest> records
) {
}
