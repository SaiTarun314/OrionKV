package com.orionkv.coordinationplane.rpc;

import com.orionkv.coordinationplane.model.ReplicaReadResult;
import com.orionkv.dataplane.model.ReplicaRecord;

public interface ReplicaDataClient {

    boolean putReplica(String targetAddress, String requestId, ReplicaRecord record);

    ReplicaReadResult getReplica(String targetAddress, String requestId, String key);
}
