package com.orionkv.controlplane.bootstrap.service;

import com.orionkv.dataplane.service.ReplicaStreamPage;

public interface BootstrapReplicaClient {

    ReplicaStreamPage streamRange(String address, long startToken, long endToken, int batchSize, String cursor);
}
