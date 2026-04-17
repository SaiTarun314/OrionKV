package com.orionkv.controlplane.bootstrap.service;

import com.orionkv.dataplane.model.ReplicaRecord;
import com.orionkv.dataplane.model.StoredValue;
import com.orionkv.dataplane.service.ReplicaStreamPage;
import com.orionkv.dataplane.service.StorageService;
import com.orionkv.controlplane.ring.model.TokenRange;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class BootstrapTransferService {

    private static final int DEFAULT_BATCH_SIZE = 100;

    private final BootstrapReplicaClient bootstrapReplicaClient;
    private final StorageService storageService;

    public BootstrapTransferService(BootstrapReplicaClient bootstrapReplicaClient, StorageService storageService) {
        this.bootstrapReplicaClient = bootstrapReplicaClient;
        this.storageService = storageService;
    }

    public void transferNewRanges(List<TokenRange> ranges, Map<TokenRange, String> donorNodeIds, String targetNodeId) {
        for (TokenRange range : ranges) {
            String donorAddress = donorNodeIds.get(range);
            if (donorAddress == null || donorAddress.isBlank()) {
                continue;
            }
            transferRange(range, donorAddress, targetNodeId);
        }
    }

    private void transferRange(TokenRange range, String donorAddress, String targetNodeId) {
        String cursor = null;
        boolean done = false;

        while (!done) {
            ReplicaStreamPage page = bootstrapReplicaClient.streamRange(
                    donorAddress,
                    range.startExclusive(),
                    range.endInclusive(),
                    DEFAULT_BATCH_SIZE,
                    cursor
            );

            if (!page.entries().isEmpty()) {
                storageService.applyReplicaBatch(page.entries().stream()
                        .map(entry -> toReplicaRecord(entry, targetNodeId))
                        .toList());
            }

            cursor = page.nextCursor();
            done = page.done();
        }
    }

    private ReplicaRecord toReplicaRecord(StoredValue entry, String targetNodeId) {
        String sourceNodeId = entry.sourceNodeId();
        if (sourceNodeId == null || sourceNodeId.isBlank()) {
            sourceNodeId = targetNodeId;
        }

        return new ReplicaRecord(
                entry.key(),
                entry.value(),
                entry.timestamp(),
                entry.tombstone(),
                entry.token(),
                sourceNodeId
        );
    }
}
