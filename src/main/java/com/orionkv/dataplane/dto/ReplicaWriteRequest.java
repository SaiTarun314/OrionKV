package com.orionkv.dataplane.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.orionkv.dataplane.model.ReplicaRecord;
import com.orionkv.dataplane.util.TokenUtil;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ReplicaWriteRequest(
        @NotBlank(message = "key is required")
        String key,

        String value,

        @NotNull(message = "timestamp is required")
        Long timestamp,

        @JsonAlias("is_deleted")
        @NotNull(message = "tombstone flag is required")
        Boolean tombstone,

        Long token,

        String sourceNodeId
) {
    public ReplicaRecord toReplicaRecord() {
        long resolvedToken = token != null ? token : TokenUtil.tokenFor(key);
        return new ReplicaRecord(key, value, timestamp, tombstone, resolvedToken, sourceNodeId);
    }

    @AssertTrue(message = "value is required for non-tombstone replica writes")
    public boolean hasValueForLiveWrite() {
        return Boolean.TRUE.equals(tombstone) || value != null;
    }
}
