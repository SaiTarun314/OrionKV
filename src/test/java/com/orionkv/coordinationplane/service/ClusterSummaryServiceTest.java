package com.orionkv.coordinationplane.service;

import com.orionkv.coordinationplane.model.ReplicaReadResult;
import com.orionkv.coordinationplane.model.ReplicaStorageStats;
import com.orionkv.coordinationplane.rpc.ReplicaDataClient;
import com.orionkv.controlplane.membership.model.MemberRecord;
import com.orionkv.controlplane.membership.model.MemberStatus;
import com.orionkv.controlplane.membership.service.MembershipService;
import com.orionkv.dataplane.model.ReplicaRecord;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClusterSummaryServiceTest {

    @Test
    void returnsGoodSummaryForBalancedCluster() {
        MembershipService membershipService = membershipWithAliveNodes(
                member("node-1", "127.0.0.1:9091"),
                member("node-2", "127.0.0.1:9092"),
                member("node-3", "127.0.0.1:9093")
        );
        StubReplicaDataClient replicaDataClient = new StubReplicaDataClient(Map.of(
                "127.0.0.1:9091", new ReplicaStorageStats(true, 100, 100, 0),
                "127.0.0.1:9092", new ReplicaStorageStats(true, 102, 102, 0),
                "127.0.0.1:9093", new ReplicaStorageStats(true, 98, 98, 0)
        ));

        ClusterSummaryService service = new ClusterSummaryService(membershipService, replicaDataClient);

        var summary = service.buildSummary();

        assertThat(summary.clusterHealth()).isEqualTo("Good");
        assertThat(summary.balance().status()).isEqualTo("Balanced");
        assertThat(summary.quickFacts().activeNodes()).isEqualTo(3);
        assertThat(summary.quickFacts().nodesResponded()).isEqualTo(3);
        assertThat(summary.quickFacts().totalRecords()).isEqualTo(300);
        assertThat(summary.distribution().nodesAboveNormalRange()).isEqualTo(0);
        assertThat(summary.distribution().nodesBelowNormalRange()).isEqualTo(0);
    }

    @Test
    void returnsWarningWhenSomeActiveNodesDoNotRespond() {
        MembershipService membershipService = membershipWithAliveNodes(
                member("node-1", "127.0.0.1:9091"),
                member("node-2", "127.0.0.1:9092"),
                member("node-3", "127.0.0.1:9093")
        );
        StubReplicaDataClient replicaDataClient = new StubReplicaDataClient(Map.of(
                "127.0.0.1:9091", new ReplicaStorageStats(true, 100, 100, 0),
                "127.0.0.1:9092", new ReplicaStorageStats(true, 120, 120, 0),
                "127.0.0.1:9093", new ReplicaStorageStats(false, 0, 0, 0)
        ));

        ClusterSummaryService service = new ClusterSummaryService(membershipService, replicaDataClient);

        var summary = service.buildSummary();

        assertThat(summary.clusterHealth()).isEqualTo("Warning");
        assertThat(summary.oneLineSummary()).contains("not reporting data");
        assertThat(summary.quickFacts().activeNodes()).isEqualTo(3);
        assertThat(summary.quickFacts().nodesResponded()).isEqualTo(2);
        assertThat(summary.whatThisMeans()).anyMatch(line -> line.contains("did not respond"));
    }

    @Test
    void returnsCriticalForSevereSkew() {
        MembershipService membershipService = membershipWithAliveNodes(
                member("node-1", "127.0.0.1:9091"),
                member("node-2", "127.0.0.1:9092"),
                member("node-3", "127.0.0.1:9093")
        );
        StubReplicaDataClient replicaDataClient = new StubReplicaDataClient(Map.of(
                "127.0.0.1:9091", new ReplicaStorageStats(true, 300, 300, 0),
                "127.0.0.1:9092", new ReplicaStorageStats(true, 100, 100, 0),
                "127.0.0.1:9093", new ReplicaStorageStats(true, 100, 100, 0)
        ));

        ClusterSummaryService service = new ClusterSummaryService(membershipService, replicaDataClient);

        var summary = service.buildSummary();

        assertThat(summary.clusterHealth()).isEqualTo("Critical");
        assertThat(summary.balance().status()).isEqualTo("Uneven");
        assertThat(summary.balance().mostLoadedNode()).isEqualTo("node-1");
        assertThat(summary.balance().leastLoadedCount()).isEqualTo(100);
    }

    @Test
    void returnsCriticalUnavailableWhenNoNodesRespond() {
        MembershipService membershipService = membershipWithAliveNodes(
                member("node-1", "127.0.0.1:9091"),
                member("node-2", "127.0.0.1:9092")
        );
        StubReplicaDataClient replicaDataClient = new StubReplicaDataClient(Map.of(
                "127.0.0.1:9091", new ReplicaStorageStats(false, 0, 0, 0),
                "127.0.0.1:9092", new ReplicaStorageStats(false, 0, 0, 0)
        ));

        ClusterSummaryService service = new ClusterSummaryService(membershipService, replicaDataClient);

        var summary = service.buildSummary();

        assertThat(summary.clusterHealth()).isEqualTo("Critical");
        assertThat(summary.balance().status()).isEqualTo("Unavailable");
        assertThat(summary.quickFacts().nodesResponded()).isZero();
        assertThat(summary.recommendedAction()).contains("connectivity");
    }

    private static MembershipService membershipWithAliveNodes(MemberRecord... members) {
        MembershipService membershipService = new MembershipService(
                Clock.fixed(Instant.parse("2026-04-22T20:00:00Z"), ZoneOffset.UTC)
        );
        membershipService.mergeRemoteMembership(List.of(members));
        return membershipService;
    }

    private static MemberRecord member(String nodeId, String address) {
        return new MemberRecord(
                nodeId,
                address,
                MemberStatus.ALIVE,
                1L,
                Instant.parse("2026-04-22T20:00:00Z")
        );
    }

    private static final class StubReplicaDataClient implements ReplicaDataClient {

        private final Map<String, ReplicaStorageStats> statsByAddress = new HashMap<>();

        private StubReplicaDataClient(Map<String, ReplicaStorageStats> statsByAddress) {
            this.statsByAddress.putAll(statsByAddress);
        }

        @Override
        public boolean putReplica(String targetAddress, String requestId, ReplicaRecord record) {
            return true;
        }

        @Override
        public ReplicaReadResult getReplica(String targetAddress, String requestId, String key) {
            return new ReplicaReadResult(false, false, key, null, -1L, -1L, false, null);
        }

        @Override
        public ReplicaStorageStats getReplicaStorageStats(String targetAddress, String requestId) {
            return statsByAddress.getOrDefault(targetAddress, new ReplicaStorageStats(false, 0, 0, 0));
        }
    }
}
