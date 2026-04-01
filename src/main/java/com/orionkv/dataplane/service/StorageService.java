package com.orionkv.dataplane.service;

import com.orionkv.dataplane.model.ReplicaRecord;
import com.orionkv.dataplane.model.StoredValue;

import java.util.List;

public interface StorageService {

    StoredValue put(String key, String value, long timestamp);

    StoredValue get(String key);

    void delete(String key, long timestamp);

    List<StoredValue> scanRange(long startToken, long endToken);

    ReplicaApplyResult applyReplicaWrite(ReplicaRecord replicaRecord);

    BatchApplyResult applyReplicaBatch(List<ReplicaRecord> replicaRecords);
}
