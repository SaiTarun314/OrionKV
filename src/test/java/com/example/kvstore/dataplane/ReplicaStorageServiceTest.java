package com.example.kvstore.dataplane;

import com.example.kvstore.dataplane.exception.KeyNotFoundException;
import com.example.kvstore.dataplane.model.ReplicaRecord;
import com.example.kvstore.dataplane.model.StoredValue;
import com.example.kvstore.dataplane.service.BatchApplyResult;
import com.example.kvstore.dataplane.service.LocalStorageService;
import com.example.kvstore.dataplane.service.ReplicaApplyResult;
import com.example.kvstore.dataplane.util.TokenUtil;
import com.example.kvstore.dataplane.storage.InMemoryStorageIndex;
import com.example.kvstore.dataplane.storage.WriteAheadLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplicaStorageServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void applyingNewerReplicaWriteOverwritesLocalValue() {
        TestHarness harness = createHarness(tempDir.resolve("newer.log"));
        LocalStorageService storageService = harness.storageService();
        long token = TokenUtil.tokenFor("user-1");

        storageService.put("user-1", "local-value", 100L);
        ReplicaApplyResult result = storageService.applyReplicaWrite(
            new ReplicaRecord("user-1", "replica-value", 200L, false, token, "node-2")
        );

        assertTrue(result.applied());
        assertEquals("replica-value", storageService.get("user-1").value());
        assertEquals(200L, storageService.get("user-1").timestamp());
    }

    @Test
    void applyingOlderReplicaWriteIsIgnored() {
        TestHarness harness = createHarness(tempDir.resolve("older.log"));
        LocalStorageService storageService = harness.storageService();
        long token = TokenUtil.tokenFor("user-2");

        storageService.put("user-2", "local-value", 500L);
        ReplicaApplyResult result = storageService.applyReplicaWrite(
            new ReplicaRecord("user-2", "stale-replica", 200L, false, token, "node-3")
        );

        assertFalse(result.applied());
        assertEquals("local-value", storageService.get("user-2").value());
        assertEquals(500L, storageService.get("user-2").timestamp());
    }

    @Test
    void replicatedTombstoneHidesPriorValue() {
        TestHarness harness = createHarness(tempDir.resolve("tombstone.log"));
        LocalStorageService storageService = harness.storageService();
        long token = TokenUtil.tokenFor("user-3");

        storageService.put("user-3", "live-value", 100L);
        ReplicaApplyResult result = storageService.applyReplicaWrite(
            new ReplicaRecord("user-3", null, 300L, true, token, "node-4")
        );

        assertTrue(result.applied());
        assertTrue(result.storedValue().tombstone());
        assertThrows(KeyNotFoundException.class, () -> storageService.get("user-3"));
    }

    @Test
    void batchBootstrapApplyRestoresTransferredRecordsCorrectly() {
        LocalStorageService sourceService = createHarness(tempDir.resolve("source.log")).storageService();
        TestHarness targetHarness = createHarness(tempDir.resolve("target.log"));
        LocalStorageService targetService = targetHarness.storageService();

        sourceService.put("alpha", "one", 100L);
        sourceService.put("beta", "two", 100L);

        List<ReplicaRecord> transferredRecords = sourceService.scanRange(0L, Long.MAX_VALUE).stream()
            .map(value -> new ReplicaRecord(
                value.key(),
                value.value(),
                value.timestamp(),
                value.tombstone(),
                value.token(),
                "source-node"
            ))
            .toList();

        BatchApplyResult result = targetService.applyReplicaBatch(transferredRecords);
        StoredValue restoredAlpha = targetService.get("alpha");
        StoredValue restoredBeta = targetService.get("beta");

        assertEquals(2, result.receivedCount());
        assertEquals(2, result.appliedCount());
        assertEquals(0, result.ignoredCount());
        assertEquals("one", restoredAlpha.value());
        assertEquals("two", restoredBeta.value());
        assertEquals(2, targetHarness.repository().loadAll().size());
    }

    @Test
    void equalTimestampUsesDeterministicTieBreakRule() {
        LocalStorageService storageService = createHarness(tempDir.resolve("tie-break.log")).storageService();
        long token = TokenUtil.tokenFor("tie-key");

        storageService.put("tie-key", "alpha", 100L);
        ReplicaApplyResult result = storageService.applyReplicaWrite(
            new ReplicaRecord("tie-key", "omega", 100L, false, token, "node-5")
        );

        assertTrue(result.applied());
        assertEquals("omega", storageService.get("tie-key").value());
    }

    @Test
    void duplicateReplicaWriteRetryIsIgnoredAndNotPersistedTwice() {
        TestHarness harness = createHarness(tempDir.resolve("duplicate-retry.log"));
        LocalStorageService storageService = harness.storageService();
        ReplicaRecord record = new ReplicaRecord(
            "retry-key",
            "value",
            100L,
            false,
            TokenUtil.tokenFor("retry-key"),
            "node-6"
        );

        ReplicaApplyResult first = storageService.applyReplicaWrite(record);
        ReplicaApplyResult second = storageService.applyReplicaWrite(record);

        assertTrue(first.applied());
        assertFalse(second.applied());
        assertEquals("value", storageService.get("retry-key").value());
        assertEquals(1, harness.repository().loadAll().size());
    }

    @Test
    void duplicateRecordsInsideBatchApplyRemainIdempotent() {
        TestHarness harness = createHarness(tempDir.resolve("duplicate-batch.log"));
        LocalStorageService storageService = harness.storageService();
        ReplicaRecord record = new ReplicaRecord(
            "batch-key",
            "value",
            100L,
            false,
            TokenUtil.tokenFor("batch-key"),
            "node-7"
        );

        BatchApplyResult result = storageService.applyReplicaBatch(List.of(record, record));

        assertEquals(2, result.receivedCount());
        assertEquals(1, result.appliedCount());
        assertEquals(1, result.ignoredCount());
        assertEquals("value", storageService.get("batch-key").value());
        assertEquals(1, harness.repository().loadAll().size());
    }

    @Test
    void replicaWritesSurviveRestartRecovery() {
        Path walPath = tempDir.resolve("replica-recovery.log");
        TestHarness firstHarness = createHarness(walPath);
        LocalStorageService firstService = firstHarness.storageService();
        ReplicaRecord record = new ReplicaRecord(
            "replica-recovery-key",
            "replicated",
            101L,
            false,
            TokenUtil.tokenFor("replica-recovery-key"),
            "node-8"
        );

        firstService.applyReplicaWrite(record);

        LocalStorageService recoveredService = createHarness(walPath).storageService();
        assertEquals("replicated", recoveredService.get("replica-recovery-key").value());
        assertEquals(101L, recoveredService.get("replica-recovery-key").timestamp());
    }

    @Test
    void batchBootstrapApplySurvivesRestartRecovery() {
        Path walPath = tempDir.resolve("batch-recovery.log");
        TestHarness firstHarness = createHarness(walPath);
        LocalStorageService firstService = firstHarness.storageService();
        ReplicaRecord first = new ReplicaRecord("boot-a", "one", 100L, false, TokenUtil.tokenFor("boot-a"), "node-9");
        ReplicaRecord second = new ReplicaRecord("boot-b", "two", 101L, false, TokenUtil.tokenFor("boot-b"), "node-9");

        firstService.applyReplicaBatch(List.of(first, second));

        LocalStorageService recoveredService = createHarness(walPath).storageService();
        assertEquals("one", recoveredService.get("boot-a").value());
        assertEquals("two", recoveredService.get("boot-b").value());
    }

    private TestHarness createHarness(Path walPath) {
        WriteAheadLogRepository repository = new WriteAheadLogRepository(walPath.toString(), new ObjectMapper());
        repository.initialize();

        LocalStorageService service = new LocalStorageService(repository, new InMemoryStorageIndex());
        service.recover();
        return new TestHarness(service, repository);
    }

    private record TestHarness(LocalStorageService storageService, WriteAheadLogRepository repository) {
    }
}
