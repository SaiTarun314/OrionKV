package com.orionkv.dataplane;

import com.orionkv.dataplane.model.StoredValue;
import com.orionkv.dataplane.service.ReadRepairService;
import com.orionkv.dataplane.service.ReplicaNodeVersion;
import com.orionkv.dataplane.service.ReplicaRepairClient;
import com.orionkv.dataplane.service.ReplicaVersionResolver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReadRepairServiceTest {

    @Test
    void readMergeReturnsLatestValue() {
        ReadRepairService service = new ReadRepairService(
                new ReplicaVersionResolver(),
                (replicaBaseUrl, latestVersion) -> {
                },
                Runnable::run
        );

        StoredValue resolved = service.mergeVersions(List.of(
                new StoredValue("user-1", "older", 100L, false, 1L, null),
                new StoredValue("user-1", "newer", 200L, false, 1L, null),
                new StoredValue("user-1", null, 150L, true, 1L, null)
        ));

        assertEquals("newer", resolved.value());
        assertEquals(200L, resolved.timestamp());
    }

    @Test
    void readRepairUpdatesStaleReplicas() {
        List<String> repairedReplicas = new CopyOnWriteArrayList<>();
        ReadRepairService service = new ReadRepairService(
                new ReplicaVersionResolver(),
                new ReplicaRepairClient() {
                    @Override
                    public void repair(String replicaBaseUrl, StoredValue latestVersion) {
                        repairedReplicas.add(replicaBaseUrl + "=" + latestVersion.timestamp());
                    }
                },
                Runnable::run
        );

        StoredValue latest = service.mergeAndRepair(List.of(
                new ReplicaNodeVersion("http://replica-a", new StoredValue("user-2", "stale", 100L, false, 2L, null)),
                new ReplicaNodeVersion("http://replica-b", new StoredValue("user-2", "fresh", 300L, false, 2L, null)),
                new ReplicaNodeVersion("http://replica-c", new StoredValue("user-2", "fresh", 300L, false, 2L, null))
        ));

        assertEquals("fresh", latest.value());
        assertEquals(List.of("http://replica-a=300"), new ArrayList<>(repairedReplicas));
    }
}
