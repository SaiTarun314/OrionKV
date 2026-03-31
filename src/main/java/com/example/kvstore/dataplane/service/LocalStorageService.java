package com.example.kvstore.dataplane.service;

import com.example.kvstore.dataplane.exception.KeyNotFoundException;
import com.example.kvstore.dataplane.model.ReplicaRecord;
import com.example.kvstore.dataplane.model.StoredValue;
import com.example.kvstore.dataplane.model.WriteAheadLogEntry;
import com.example.kvstore.dataplane.storage.ApplyResult;
import com.example.kvstore.dataplane.storage.InMemoryStorageIndex;
import com.example.kvstore.dataplane.storage.PersistentStorage;
import com.example.kvstore.dataplane.util.TokenUtil;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
public class LocalStorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(LocalStorageService.class);

    private final PersistentStorage persistentStorage;
    private final InMemoryStorageIndex inMemoryStorageIndex;
    private final Object mutationLock = new Object();

    public LocalStorageService(
        PersistentStorage persistentStorage,
        InMemoryStorageIndex inMemoryStorageIndex
    ) {
        this.persistentStorage = persistentStorage;
        this.inMemoryStorageIndex = inMemoryStorageIndex;
    }

    @PostConstruct
    public void recover() {
        int recoveredEntryCount = 0;
        for (WriteAheadLogEntry entry : persistentStorage.loadAll()) {
            inMemoryStorageIndex.apply(entry);
            recoveredEntryCount++;
        }
        log.info("Recovered {} persisted storage entries into the local index", recoveredEntryCount);
    }

    @Override
    public StoredValue put(String key, String value, long timestamp) {
        long token = TokenUtil.tokenFor(key);
        WriteAheadLogEntry entry = WriteAheadLogEntry.put(key, value, timestamp, token);
        return applyMutation(entry);
    }

    @Override
    public StoredValue get(String key) {
        StoredValue storedValue = inMemoryStorageIndex.get(key)
            .filter(value -> !value.tombstone())
            .orElseThrow(() -> new KeyNotFoundException(key));

        return storedValue;
    }

    @Override
    public void delete(String key, long timestamp) {
        long token = TokenUtil.tokenFor(key);
        WriteAheadLogEntry entry = WriteAheadLogEntry.delete(key, timestamp, token);
        applyMutation(entry);
    }

    @Override
    public List<StoredValue> scanRange(long startToken, long endToken) {
        return inMemoryStorageIndex.scanRange(startToken, endToken).stream()
            .filter(value -> !value.tombstone())
            .sorted(Comparator.comparingLong(StoredValue::token).thenComparing(StoredValue::key))
            .toList();
    }

    @Override
    public ReplicaApplyResult applyReplicaWrite(ReplicaRecord replicaRecord) {
        WriteAheadLogEntry entry = toWriteAheadLogEntry(replicaRecord);
        ApplyResult applyResult = persistAndApply(entry);
        log.info(
            "Replica write processed for key='{}', sourceNodeId='{}', applied={}, tombstone={}, timestamp={}",
            replicaRecord.key(),
            replicaRecord.sourceNodeId(),
            applyResult.applied(),
            replicaRecord.tombstone(),
            replicaRecord.timestamp()
        );
        return new ReplicaApplyResult(applyResult.storedValue(), applyResult.applied());
    }

    @Override
    public BatchApplyResult applyReplicaBatch(List<ReplicaRecord> replicaRecords) {
        int appliedCount = 0;

        for (ReplicaRecord replicaRecord : replicaRecords) {
            WriteAheadLogEntry entry = toWriteAheadLogEntry(replicaRecord);
            ApplyResult applyResult = persistAndApply(entry);
            if (applyResult.applied()) {
                appliedCount++;
            }
        }

        BatchApplyResult result = new BatchApplyResult(
            replicaRecords.size(),
            appliedCount,
            replicaRecords.size() - appliedCount
        );
        log.info(
            "Replica batch apply completed: receivedCount={}, appliedCount={}, ignoredCount={}",
            result.receivedCount(),
            result.appliedCount(),
            result.ignoredCount()
        );
        return result;
    }

    private StoredValue applyMutation(WriteAheadLogEntry entry) {
        return persistAndApply(entry).storedValue();
    }

    private ApplyResult persistAndApply(WriteAheadLogEntry entry) {
        synchronized (mutationLock) {
            ApplyResult preview = inMemoryStorageIndex.preview(entry);
            if (!preview.applied()) {
                return preview;
            }

            // Persist only accepted mutations so retries and stale replicas stay idempotent.
            persistentStorage.append(entry);
            return inMemoryStorageIndex.apply(entry);
        }
    }

    private WriteAheadLogEntry toWriteAheadLogEntry(ReplicaRecord replicaRecord) {
        if (replicaRecord.tombstone()) {
            return WriteAheadLogEntry.delete(replicaRecord.key(), replicaRecord.timestamp(), replicaRecord.token());
        }

        return WriteAheadLogEntry.put(
            replicaRecord.key(),
            replicaRecord.value(),
            replicaRecord.timestamp(),
            replicaRecord.token()
        );
    }
}
