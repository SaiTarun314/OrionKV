package com.example.kvstore.dataplane.service;

public record BatchApplyResult(
    int receivedCount,
    int appliedCount,
    int ignoredCount
) {
}
