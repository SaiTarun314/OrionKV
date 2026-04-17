package com.orionkv.dataplane.storage;

import com.orionkv.dataplane.model.StoredValue;
import com.orionkv.dataplane.model.WriteAheadLogEntry;
import com.orionkv.dataplane.util.VersionOrdering;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class InMemoryStorageIndex {

    private final ConcurrentMap<String, StoredValue> primaryIndex = new ConcurrentHashMap<>();

    public ApplyResult preview(WriteAheadLogEntry entry) {
        StoredValue existingValue = primaryIndex.get(entry.key());
        if (existingValue == null || compare(entry, existingValue) > 0) {
            return new ApplyResult(toStoredValue(entry), true);
        }

        return new ApplyResult(existingValue, false);
    }

    public ApplyResult apply(WriteAheadLogEntry entry) {
        AtomicBoolean applied = new AtomicBoolean(false);

        StoredValue storedValue = primaryIndex.compute(entry.key(), (key, existingValue) -> {
            if (existingValue == null || compare(entry, existingValue) > 0) {
                applied.set(true);
                return toStoredValue(entry);
            }

            return existingValue;
        });

        return new ApplyResult(storedValue, applied.get());
    }

    public Optional<StoredValue> get(String key) {
        return Optional.ofNullable(primaryIndex.get(key));
    }

    public List<StoredValue> scanRange(long startToken, long endToken) {
        return primaryIndex.values().stream()
                .filter(value -> tokenInRange(value.token(), startToken, endToken))
                .toList();
    }

    private boolean tokenInRange(long token, long startToken, long endToken) {
        if (startToken <= endToken) {
            return token >= startToken && token <= endToken;
        }

        return token >= startToken || token <= endToken;
    }

    private int compare(WriteAheadLogEntry entry, StoredValue existingValue) {
        return VersionOrdering.compare(entry, existingValue);
    }

    private StoredValue toStoredValue(WriteAheadLogEntry entry) {
        return new StoredValue(
                entry.key(),
                entry.value(),
                entry.timestamp(),
                entry.tombstone(),
                entry.token()
        );
    }
}
