package com.orionkv.dataplane.util;

import java.util.Comparator;
import java.util.Objects;

import com.orionkv.dataplane.model.StoredValue;
import com.orionkv.dataplane.model.WriteAheadLogEntry;

public final class VersionOrdering {

    private static final Comparator<StoredValue> STORED_VALUE_COMPARATOR = (left, right) -> {
        int timestampComparison = Long.compare(left.timestamp(), right.timestamp());
        if (timestampComparison != 0) {
            return timestampComparison;
        }

        int tombstoneComparison = Boolean.compare(left.tombstone(), right.tombstone());
        if (tombstoneComparison != 0) {
            return tombstoneComparison;
        }

        String leftValue = Objects.toString(left.value(), "");
        String rightValue = Objects.toString(right.value(), "");
        return leftValue.compareTo(rightValue);
    };

    private VersionOrdering() {
    }

    public static int compare(WriteAheadLogEntry entry, StoredValue existingValue) {
        return compare(
                new StoredValue(
                        entry.key(),
                        entry.value(),
                        entry.timestamp(),
                        entry.tombstone(),
                        entry.token(),
                        entry.sourceNodeId()
                ),
                existingValue
        );
    }

    public static int compare(StoredValue left, StoredValue right) {
        return STORED_VALUE_COMPARATOR.compare(left, right);
    }
}
