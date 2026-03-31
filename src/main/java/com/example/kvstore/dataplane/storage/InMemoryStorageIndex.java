package com.example.kvstore.dataplane.storage;

import com.example.kvstore.dataplane.model.StoredValue;
import com.example.kvstore.dataplane.model.WriteAheadLogEntry;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

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

        // Ring-style wraparound support for future partition ownership changes.
        return token >= startToken || token <= endToken;
    }

    private int compare(WriteAheadLogEntry entry, StoredValue existingValue) {
        int timestampComparison = Long.compare(entry.timestamp(), existingValue.timestamp());
        if (timestampComparison != 0) {
            return timestampComparison;
        }

        int tombstoneComparison = Boolean.compare(entry.tombstone(), existingValue.tombstone());
        if (tombstoneComparison != 0) {
            return tombstoneComparison;
        }

        String incomingValue = Objects.toString(entry.value(), "");
        String existingStoredValue = Objects.toString(existingValue.value(), "");
        return incomingValue.compareTo(existingStoredValue);
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
