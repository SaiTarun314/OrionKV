package com.orionkv.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orionkv.config.NodeProperties;
import com.orionkv.controlplane.membership.model.MemberRecord;
import com.orionkv.controlplane.membership.model.MemberStatus;
import com.orionkv.controlplane.membership.service.MembershipService;
import com.orionkv.controlplane.ring.service.HashRingService;
import com.orionkv.controlplane.ring.service.VirtualNodeService;
import com.orionkv.coordinationplane.model.ReplicaReadResult;
import com.orionkv.coordinationplane.model.ReplicaRoute;
import com.orionkv.coordinationplane.rpc.ReplicaDataClient;
import com.orionkv.coordinationplane.service.QuorumCoordinatorService;
import com.orionkv.coordinationplane.service.QuorumService;
import com.orionkv.coordinationplane.service.ReplicaRoutingService;
import com.orionkv.dataplane.model.ReplicaRecord;
import com.orionkv.dataplane.service.LocalStorageService;
import com.orionkv.dataplane.storage.InMemoryStorageIndex;
import com.orionkv.dataplane.storage.WriteAheadLogRepository;
import com.orionkv.proto.ClientGetResponse;
import com.orionkv.proto.ClientPutResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class QuorumFlowIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void quorumWriteAndReadSucceedWithOneDeadReplica() {
        MembershipService membershipService = new MembershipService(
                Clock.fixed(Instant.parse("2026-04-18T16:00:00Z"), ZoneOffset.UTC)
        );

        NodeProperties nodeProperties = new NodeProperties();
        nodeProperties.setNodeId("node-a");
        nodeProperties.setAddress("127.0.0.1:9091");
        nodeProperties.setVirtualNodeCount(8);
        nodeProperties.setReplicationFactor(3);
        nodeProperties.setWriteQuorum(2);
        nodeProperties.setReadQuorum(2);

        membershipService.mergeRemoteMembership(List.of(
                member("node-a", "127.0.0.1:9091"),
                member("node-b", "127.0.0.1:9092"),
                member("node-c", "127.0.0.1:9093")
        ));

        HashRingService hashRingService = new HashRingService(new VirtualNodeService(), nodeProperties);
        hashRingService.rebuildRing(membershipService.getMembershipSnapshot());
        ReplicaRoutingService replicaRoutingService = new ReplicaRoutingService(hashRingService);

        WriteAheadLogRepository repository = new WriteAheadLogRepository(
                tempDir.resolve("quorum-flow.wal.log").toString(),
                new ObjectMapper()
        );
        repository.initialize();
        LocalStorageService localStorageService = new LocalStorageService(repository, new InMemoryStorageIndex());
        localStorageService.recover();

        RecordingReplicaDataClient replicaDataClient = new RecordingReplicaDataClient();
        QuorumCoordinatorService quorumCoordinatorService = new QuorumCoordinatorService(
                nodeProperties,
                replicaRoutingService,
                new QuorumService(),
                membershipService,
                localStorageService,
                replicaDataClient
        );

        String key = "quorum-live-key";
        membershipService.markDead("node-c");
        hashRingService.rebuildRing(membershipService.getMembershipSnapshot());

        ClientPutResponse putResponse = quorumCoordinatorService.put("put-1", key, "value-1", 1_710_000_000_000L);
        assertThat(putResponse.getSuccess()).isTrue();
        assertThat(putResponse.getAckCount()).isGreaterThanOrEqualTo(2);

        ReplicaRoute routeAfterDead = replicaRoutingService.routeForKey(key);
        assertThat(routeAfterDead.replicaNodeIds()).doesNotContain("node-c");

        ClientGetResponse getResponse = quorumCoordinatorService.get("get-1", key);
        assertThat(getResponse.getFound()).isTrue();
        assertThat(getResponse.getValue()).isEqualTo("value-1");
        assertThat(getResponse.getResponseCount()).isGreaterThanOrEqualTo(2);
    }

    private static MemberRecord member(String nodeId, String address) {
        return new MemberRecord(
                nodeId,
                address,
                MemberStatus.ALIVE,
                1L,
                Instant.parse("2026-04-18T16:00:00Z")
        );
    }

    private static final class RecordingReplicaDataClient implements ReplicaDataClient {

        private final Map<String, ReplicaRecord> byAddress = new HashMap<>();

        @Override
        public boolean putReplica(String targetAddress, String requestId, ReplicaRecord record) {
            if (targetAddress.endsWith(":9092")) {
                byAddress.put(targetAddress + "|" + record.key(), record);
                return true;
            }
            return false;
        }

        @Override
        public ReplicaReadResult getReplica(String targetAddress, String requestId, String key) {
            ReplicaRecord record = byAddress.get(targetAddress + "|" + key);
            if (record == null) {
                return new ReplicaReadResult(true, false, key, null, -1L, -1L, false, "node-b");
            }
            return new ReplicaReadResult(
                    true,
                    true,
                    record.key(),
                    record.value(),
                    record.token(),
                    record.timestamp(),
                    record.tombstone(),
                    "node-b"
            );
        }
    }
}
