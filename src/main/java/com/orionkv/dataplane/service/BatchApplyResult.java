package com.orionkv.dataplane.service;

public record BatchApplyResult(
        int receivedCount,
        int appliedCount,
        int ignoredCount
) {
}
