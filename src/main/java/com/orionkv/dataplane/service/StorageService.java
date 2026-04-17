package com.orionkv.dataplane.service;

import com.orionkv.dataplane.model.ReplicaRecord;
import com.orionkv.dataplane.model.StoredValue;

import java.util.List;
import java.util.Optional;

public interface StorageService {

    StoredValue put(String key, String value, long timestamp);

    StoredValue get(String key);

    Optional<StoredValue> getVersioned(String key);

    void delete(String key, long timestamp);

    List<StoredValue> scanRange(long startToken, long endToken);

    List<StoredValue> scanActiveRange(long startToken, long endToken);

    ReplicaStreamPage scanRangePage(long startToken, long endToken, int batchSize, String cursor);

    ReplicaApplyResult applyReplicaWrite(ReplicaRecord replicaRecord);

    BatchApplyResult applyReplicaBatch(List<ReplicaRecord> replicaRecords);
}
