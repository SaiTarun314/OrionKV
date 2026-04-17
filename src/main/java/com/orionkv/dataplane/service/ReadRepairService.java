package com.orionkv.dataplane.service;

import com.orionkv.dataplane.model.StoredValue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

@Service
public class ReadRepairService {

    private final ReplicaVersionResolver replicaVersionResolver;
    private final ReplicaRepairClient replicaRepairClient;
    private final Executor executor;

    @Autowired
    public ReadRepairService(
            ReplicaVersionResolver replicaVersionResolver,
            ReplicaRepairClient replicaRepairClient
    ) {
        this(replicaVersionResolver, replicaRepairClient, ForkJoinPool.commonPool());
    }

    public ReadRepairService(
            ReplicaVersionResolver replicaVersionResolver,
            ReplicaRepairClient replicaRepairClient,
            Executor executor
    ) {
        this.replicaVersionResolver = replicaVersionResolver;
        this.replicaRepairClient = replicaRepairClient;
        this.executor = executor;
    }

    public StoredValue mergeVersions(List<StoredValue> versions) {
        return replicaVersionResolver.mergeVersions(versions);
    }

    public StoredValue mergeAndRepair(List<ReplicaNodeVersion> replicaVersions) {
        StoredValue latest = replicaVersionResolver.mergeVersions(
                replicaVersions.stream().map(ReplicaNodeVersion::version).toList()
        );

        for (ReplicaNodeVersion replicaNodeVersion : replicaVersions) {
            if (replicaVersionResolver.isStale(replicaNodeVersion.version(), latest)) {
                CompletableFuture.runAsync(
                        () -> replicaRepairClient.repair(replicaNodeVersion.replicaBaseUrl(), latest),
                        executor
                ).exceptionally(ex -> null);
            }
        }

        return latest;
    }
}
