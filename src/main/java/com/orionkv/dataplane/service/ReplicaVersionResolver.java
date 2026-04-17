package com.orionkv.dataplane.service;

import com.orionkv.dataplane.model.StoredValue;
import com.orionkv.dataplane.util.VersionOrdering;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ReplicaVersionResolver {

    public StoredValue mergeVersions(List<StoredValue> versions) {
        return versions.stream()
                .max(VersionOrdering::compare)
                .orElseThrow(() -> new IllegalArgumentException("versions must not be empty"));
    }

    public boolean isStale(StoredValue candidate, StoredValue latest) {
        return VersionOrdering.compare(candidate, latest) < 0;
    }
}
