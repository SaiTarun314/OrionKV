package com.orionkv.dataplane.dto;

import com.orionkv.dataplane.service.BatchApplyResult;

public record ReplicaBatchApplyResponse(
        int receivedCount,
        int appliedCount,
        int ignoredCount
) {
    public static ReplicaBatchApplyResponse from(BatchApplyResult result) {
        return new ReplicaBatchApplyResponse(
                result.receivedCount(),
                result.appliedCount(),
                result.ignoredCount()
        );
    }
}
