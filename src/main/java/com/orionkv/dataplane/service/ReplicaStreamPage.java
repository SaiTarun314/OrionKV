package com.orionkv.dataplane.service;

import com.orionkv.dataplane.model.StoredValue;

import java.util.List;

public record ReplicaStreamPage(
        List<StoredValue> entries,
        String nextCursor,
        boolean done
) {
}
