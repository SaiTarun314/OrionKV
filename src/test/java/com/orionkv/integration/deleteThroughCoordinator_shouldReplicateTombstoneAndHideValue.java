package com.orionkv.integration;

import com.orionkv.config.NodeProperties;
import com.orionkv.coordinationplane.model.ReplicaReadResult;
import com.orionkv.coordinationplane.model.ReplicaRoute;
import com.orionkv.coordinationplane.rpc.ReplicaDataClient;
import com.orionkv.coordinationplane.service.QuorumCoordinatorService;
import com.orionkv.coordinationplane.service.QuorumService;
import com.orionkv.coordinationplane.service.ReplicaRoutingService;
import com.orionkv.controlplane.membership.model.MemberRecord;
import com.orionkv.controlplane.membership.model.MemberStatus;
import com.orionkv.controlplane.membership.service.MembershipService;
import com.orionkv.dataplane.exception.KeyNotFoundException;
import com.orionkv.dataplane.model.ReplicaRecord;
import com.orionkv.dataplane.model.StoredValue;
import com.orionkv.dataplane.service.BatchApplyResult;
import com.orionkv.dataplane.service.ReplicaApplyResult;
import com.orionkv.dataplane.service.ReplicaStreamPage;
import com.orionkv.dataplane.service.StorageService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class deleteThroughCoordinator_shouldReplicateTombstoneAndHideValue {

    @Test
    void deleteThroughCoordinator_shouldReplicateTombstoneAndHideValue() {
        NodeProperties nodeProperties = new NodeProperties();
        nodeProperties.setNodeId("node-a");
        nodeProperties.setReplicationFactor(3);
        nodeProperties.setWriteQuorum(2);
        nodeProperties.setReadQuorum(2);

        MembershipService membershipService = new MembershipService(
                Clock.fixed(Instant.parse("2026-04-20T18:25:00Z"), ZoneOffset.UTC)
        );
        membershipService.mergeRemoteMembership(List.of(
                member("node-a", "127.0.0.1:9091"),
                member("node-b", "127.0.0.1:9092"),
                member("node-c", "127.0.0.1:9093")
        ));

        InMemoryStorage storage = new InMemoryStorage();
        List<ReplicaRecord> remoteWrites = new ArrayList<>();
        ReplicaDataClient replicaDataClient = new ReplicaDataClient() {
            @Override
            public boolean putReplica(String targetAddress, String requestId, ReplicaRecord record) {
                remoteWrites.add(record);
                return true;
            }

            @Override
            public ReplicaReadResult getReplica(String targetAddress, String requestId, String key) {
                return new ReplicaReadResult(true, true, key, null, 202L, 2000L, true, "node-b");
            }
        };

        ReplicaRoutingService routingService = new FixedRouteService(
                new ReplicaRoute("account-1", 202L, List.of("node-a", "node-b", "node-c"))
        );

        QuorumCoordinatorService coordinatorService = new QuorumCoordinatorService(
                nodeProperties,
                routingService,
                new QuorumService(),
                membershipService,
                storage,
                replicaDataClient
        );

        coordinatorService.put("req-put-1", "account-1", "active", 1000L);
        var deleteResponse = coordinatorService.delete("req-del-1", "account-1", 2000L);
        var getResponse = coordinatorService.get("req-get-1", "account-1");

        assertThat(deleteResponse.getSuccess()).isTrue();
        assertThat(remoteWrites).anyMatch(ReplicaRecord::tombstone);
        assertThat(storage.getVersioned("account-1")).get().extracting(StoredValue::tombstone).isEqualTo(true);
        assertThat(getResponse.getFound()).isFalse();
    }

    private static MemberRecord member(String nodeId, String address) {
        return new MemberRecord(nodeId, address, MemberStatus.ALIVE, 1L, Instant.parse("2026-04-20T18:25:00Z"));
    }

    private static final class FixedRouteService extends ReplicaRoutingService {
        private final ReplicaRoute route;

        private FixedRouteService(ReplicaRoute route) {
            super(null);
            this.route = route;
        }

        @Override
        public ReplicaRoute routeForKey(String key) {
            return route;
        }
    }

    private static final class InMemoryStorage implements StorageService {
        private final Map<String, StoredValue> map = new ConcurrentHashMap<>();

        @Override
        public StoredValue put(String key, String value, long timestamp) {
            throw new UnsupportedOperationException();
        }

        @Override
        public StoredValue get(String key) {
            StoredValue value = map.get(key);
            if (value == null || value.tombstone()) {
                throw new KeyNotFoundException(key);
            }
            return value;
        }

        @Override
        public Optional<StoredValue> getVersioned(String key) {
            return Optional.ofNullable(map.get(key));
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
            map.put(
                    replicaRecord.key(),
                    new StoredValue(
                            replicaRecord.key(),
                            replicaRecord.value(),
                            replicaRecord.timestamp(),
                            replicaRecord.tombstone(),
                            replicaRecord.token(),
                            replicaRecord.sourceNodeId()
                    )
            );
            return new ReplicaApplyResult(map.get(replicaRecord.key()), true);
        }

        @Override
        public BatchApplyResult applyReplicaBatch(List<ReplicaRecord> replicaRecords) {
            return new BatchApplyResult(replicaRecords.size(), replicaRecords.size(), 0);
        }

        @Override
        public void resetLocalState() {
            map.clear();
        }
    }
}

