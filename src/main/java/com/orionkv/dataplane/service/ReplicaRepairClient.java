package com.orionkv.dataplane.service;

import com.orionkv.dataplane.model.StoredValue;

public interface ReplicaRepairClient {

    void repair(String replicaBaseUrl, StoredValue latestVersion);
}
