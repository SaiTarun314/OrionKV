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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class quorumRead_shouldReturnDeletedWhenNewestVersionIsTombstone {

    @Test
    void quorumRead_shouldReturnDeletedWhenNewestVersionIsTombstone() {
        NodeProperties nodeProperties = new NodeProperties();
        nodeProperties.setNodeId("node-a");
        nodeProperties.setReplicationFactor(3);
        nodeProperties.setWriteQuorum(2);
        nodeProperties.setReadQuorum(2);

        MembershipService membershipService = new MembershipService(
                Clock.fixed(Instant.parse("2026-04-20T18:20:00Z"), ZoneOffset.UTC)
        );
        membershipService.mergeRemoteMembership(List.of(
                member("node-a", "127.0.0.1:9091"),
                member("node-b", "127.0.0.1:9092"),
                member("node-c", "127.0.0.1:9093")
        ));

        InMemoryStorage storage = new InMemoryStorage();
        storage.map.put("user-1", new StoredValue("user-1", "old-value", 100L, false, 101L, "node-a"));

        ReplicaRoutingService routingService = new FixedRouteService(
                new ReplicaRoute("user-1", 101L, List.of("node-a", "node-b", "node-c"))
        );
        ReplicaDataClient replicaDataClient = new ReplicaDataClient() {
            @Override
            public boolean putReplica(String targetAddress, String requestId, ReplicaRecord record) {
                return true;
            }

            @Override
            public ReplicaReadResult getReplica(String targetAddress, String requestId, String key) {
                if ("127.0.0.1:9092".equals(targetAddress)) {
                    return new ReplicaReadResult(true, true, key, null, 101L, 300L, true, "node-b");
                }
                return new ReplicaReadResult(true, true, key, "stale-value", 101L, 200L, false, "node-c");
            }
        };

        QuorumCoordinatorService coordinatorService = new QuorumCoordinatorService(
                nodeProperties,
                routingService,
                new QuorumService(),
                membershipService,
                storage,
                replicaDataClient
        );

        var response = coordinatorService.get("req-tombstone-1", "user-1");

        assertThat(response.getFound()).isFalse();
        assertThat(response.getMessage()).isEqualTo("not found");
    }

    private static MemberRecord member(String nodeId, String address) {
        return new MemberRecord(nodeId, address, MemberStatus.ALIVE, 1L, Instant.parse("2026-04-20T18:20:00Z"));
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
            StoredValue existing = map.get(replicaRecord.key());
            boolean applied = existing == null || replicaRecord.timestamp() > existing.timestamp();
            if (applied) {
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
            }
            return new ReplicaApplyResult(map.get(replicaRecord.key()), applied);
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

