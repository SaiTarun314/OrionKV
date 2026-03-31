package com.example.kvstore.dataplane.service;

import com.example.kvstore.dataplane.model.ReplicaRecord;
import com.example.kvstore.dataplane.model.StoredValue;

import java.util.List;

public interface StorageService {

    StoredValue put(String key, String value, long timestamp);

    StoredValue get(String key);

    void delete(String key, long timestamp);

    List<StoredValue> scanRange(long startToken, long endToken);

    ReplicaApplyResult applyReplicaWrite(ReplicaRecord replicaRecord);

    BatchApplyResult applyReplicaBatch(List<ReplicaRecord> replicaRecords);
}
