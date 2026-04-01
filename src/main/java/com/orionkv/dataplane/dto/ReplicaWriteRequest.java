package com.orionkv.dataplane.dto;

import com.orionkv.dataplane.model.ReplicaRecord;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ReplicaWriteRequest(
        @NotBlank(message = "key is required")
        String key,

        String value,

        @NotNull(message = "timestamp is required")
        Long timestamp,

        @NotNull(message = "tombstone flag is required")
        Boolean tombstone,

        @NotNull(message = "token is required")
        Long token,

        String sourceNodeId
) {
    public ReplicaRecord toReplicaRecord() {
        return new ReplicaRecord(key, value, timestamp, tombstone, token, sourceNodeId);
    }

    @AssertTrue(message = "value is required for non-tombstone replica writes")
    public boolean hasValueForLiveWrite() {
        return Boolean.TRUE.equals(tombstone) || value != null;
    }
}
