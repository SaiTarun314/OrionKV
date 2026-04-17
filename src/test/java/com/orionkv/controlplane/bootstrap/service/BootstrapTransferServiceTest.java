package com.orionkv.controlplane.bootstrap.service;

import com.orionkv.controlplane.ring.model.TokenRange;
import com.orionkv.dataplane.model.ReplicaRecord;
import com.orionkv.dataplane.model.StoredValue;
import com.orionkv.dataplane.service.BatchApplyResult;
import com.orionkv.dataplane.service.ReplicaApplyResult;
import com.orionkv.dataplane.service.ReplicaStreamPage;
import com.orionkv.dataplane.service.StorageService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BootstrapTransferServiceTest {

    @Test
    void transferNewRangesStreamsPagesAndAppliesRecords() {
        RecordingBootstrapReplicaClient bootstrapReplicaClient = new RecordingBootstrapReplicaClient();
        RecordingStorageService storageService = new RecordingStorageService();
        BootstrapTransferService bootstrapTransferService = new BootstrapTransferService(
                bootstrapReplicaClient,
                storageService
        );

        TokenRange range = new TokenRange(10L, 20L, "node-new");

        bootstrapTransferService.transferNewRanges(
                List.of(range),
                Map.of(range, "node-a"),
                "node-new"
        );

        assertThat(bootstrapReplicaClient.cursors).containsExactly(null, "1");
        assertThat(storageService.appliedRecords)
                .hasSize(2)
                .extracting(ReplicaRecord::key)
                .containsExactly("key-a", "key-b");
        assertThat(storageService.appliedRecords)
                .extracting(ReplicaRecord::tombstone)
                .containsExactly(false, true);
    }

    private static final class RecordingBootstrapReplicaClient implements BootstrapReplicaClient {

        private final List<String> cursors = new ArrayList<>();

        @Override
        public ReplicaStreamPage streamRange(String address, long startToken, long endToken, int batchSize, String cursor) {
            cursors.add(cursor);
            if (cursor == null) {
                return new ReplicaStreamPage(
                        List.of(new StoredValue("key-a", "value-a", 100L, false, 11L, null)),
                        "1",
                        false
                );
            }

            return new ReplicaStreamPage(
                    List.of(new StoredValue("key-b", null, 200L, true, 12L, null)),
                    null,
                    true
            );
        }
    }

    private static final class RecordingStorageService implements StorageService {

        private final List<ReplicaRecord> appliedRecords = new ArrayList<>();

        @Override
        public StoredValue put(String key, String value, long timestamp) {
            throw new UnsupportedOperationException();
        }

        @Override
        public StoredValue get(String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<StoredValue> getVersioned(String key) {
            return Optional.empty();
        }

        @Override
        public void delete(String key, long timestamp) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<StoredValue> scanRange(long startToken, long endToken) {
            return List.of();
        }

        @Override
        public List<StoredValue> scanActiveRange(long startToken, long endToken) {
            return List.of();
        }

        @Override
        public ReplicaStreamPage scanRangePage(long startToken, long endToken, int batchSize, String cursor) {
            return new ReplicaStreamPage(List.of(), null, true);
        }

        @Override
        public ReplicaApplyResult applyReplicaWrite(ReplicaRecord replicaRecord) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BatchApplyResult applyReplicaBatch(List<ReplicaRecord> records) {
            appliedRecords.addAll(records);
            return new BatchApplyResult(records.size(), records.size(), 0);
        }
    }
}
