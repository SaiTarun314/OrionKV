package com.orionkv.coordinationplane.service;

import com.orionkv.coordinationplane.model.ReplicaStorageStats;
import com.orionkv.coordinationplane.rpc.ReplicaDataClient;
import com.orionkv.controlplane.membership.model.MemberRecord;
import com.orionkv.controlplane.membership.model.MemberStatus;
import com.orionkv.controlplane.membership.service.MembershipService;
import com.orionkv.dataplane.dto.ClusterSummaryResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ClusterSummaryService {

    private static final double NORMAL_RANGE_MULTIPLIER = 0.20;

    private final MembershipService membershipService;
    private final ReplicaDataClient replicaDataClient;

    public ClusterSummaryService(MembershipService membershipService, ReplicaDataClient replicaDataClient) {
        this.membershipService = membershipService;
        this.replicaDataClient = replicaDataClient;
    }

    public ClusterSummaryResponse buildSummary() {
        List<MemberRecord> activeMembers = membershipService.getMembershipSnapshot().stream()
                .filter(member -> member.status() == MemberStatus.ALIVE)
                .filter(member -> member.address() != null && !member.address().isBlank())
                .toList();

        List<NodeCount> nodeCounts = new ArrayList<>();
        for (MemberRecord member : activeMembers) {
            ReplicaStorageStats stats = replicaDataClient.getReplicaStorageStats(
                    member.address(),
                    "cluster-summary-" + member.nodeId() + "-" + System.currentTimeMillis()
            );
            if (stats.reachable()) {
                nodeCounts.add(new NodeCount(member.nodeId(), stats.totalRecords()));
            }
        }

        int activeNodes = activeMembers.size();
        int nodesResponded = nodeCounts.size();
        long totalRecords = nodeCounts.stream().mapToLong(NodeCount::count).sum();
        long averagePerNode = nodesResponded == 0 ? 0L : Math.round((double) totalRecords / nodesResponded);

        if (nodesResponded == 0) {
            return new ClusterSummaryResponse(
                    Instant.now(),
                    "Critical",
                    "red",
                    "No active nodes responded with storage data.",
                    new ClusterSummaryResponse.QuickFacts(activeNodes, 0, 0L, 0L),
                    new ClusterSummaryResponse.Balance(
                            "Unavailable", "", "", 0L, 0L, 0L, 0.0
                    ),
                    new ClusterSummaryResponse.Distribution(
                            0, 0, "within +/-20% of average"
                    ),
                    List.of(
                            "The summary could not collect record counts from active nodes.",
                            "This usually means cluster reachability or transport issues."
                    ),
                    "Verify node health and network connectivity, then retry."
            );
        }

        NodeCount mostLoaded = nodeCounts.stream().max(Comparator.comparingLong(NodeCount::count)).orElseThrow();
        NodeCount leastLoaded = nodeCounts.stream().min(Comparator.comparingLong(NodeCount::count)).orElseThrow();
        long gapRecords = mostLoaded.count() - leastLoaded.count();
        double gapPercentVsAverage = averagePerNode == 0 ? 0.0 : round1((gapRecords * 100.0) / averagePerNode);

        long upperBound = Math.round(averagePerNode * (1.0 + NORMAL_RANGE_MULTIPLIER));
        long lowerBound = Math.round(averagePerNode * (1.0 - NORMAL_RANGE_MULTIPLIER));
        int nodesAboveRange = (int) nodeCounts.stream().filter(node -> node.count() > upperBound).count();
        int nodesBelowRange = (int) nodeCounts.stream().filter(node -> node.count() < lowerBound).count();

        double cv = coefficientOfVariation(nodeCounts, averagePerNode);
        double maxMinRatio = leastLoaded.count() == 0 ? Double.POSITIVE_INFINITY : (double) mostLoaded.count() / leastLoaded.count();

        HealthAssessment assessment = assessHealth(cv, maxMinRatio, activeNodes, nodesResponded);

        List<String> explanations = new ArrayList<>();
        explanations.add(String.format("Average records per responding node is %,d.", averagePerNode));
        explanations.add(String.format(
                "Highest vs lowest node gap is %,d records (%.1f%% of average).",
                gapRecords,
                gapPercentVsAverage
        ));
        if (nodesResponded < activeNodes) {
            explanations.add(String.format(
                    "%d active node(s) did not respond and are excluded from balance math.",
                    activeNodes - nodesResponded
            ));
        }

        return new ClusterSummaryResponse(
                Instant.now(),
                assessment.clusterHealth(),
                assessment.healthColor(),
                assessment.oneLineSummary(),
                new ClusterSummaryResponse.QuickFacts(activeNodes, nodesResponded, totalRecords, averagePerNode),
                new ClusterSummaryResponse.Balance(
                        assessment.balanceStatus(),
                        mostLoaded.nodeId(),
                        leastLoaded.nodeId(),
                        mostLoaded.count(),
                        leastLoaded.count(),
                        gapRecords,
                        gapPercentVsAverage
                ),
                new ClusterSummaryResponse.Distribution(
                        nodesAboveRange,
                        nodesBelowRange,
                        "within +/-20% of average"
                ),
                explanations,
                assessment.recommendedAction()
        );
    }

    private HealthAssessment assessHealth(double cv, double maxMinRatio, int activeNodes, int nodesResponded) {
        if (nodesResponded < activeNodes) {
            return new HealthAssessment(
                    "Warning",
                    "amber",
                    "Balance looks measurable, but some active nodes are not reporting data.",
                    "Slightly Uneven",
                    "Confirm connectivity for non-responding nodes and recheck summary."
            );
        }

        if (cv <= 0.15 && maxMinRatio <= 1.50) {
            return new HealthAssessment(
                    "Good",
                    "green",
                    "Data is fairly balanced across active nodes.",
                    "Balanced",
                    "No immediate action needed. Keep monitoring."
            );
        }

        if (cv <= 0.25 && maxMinRatio <= 2.00) {
            return new HealthAssessment(
                    "Warning",
                    "amber",
                    "Data distribution is slightly uneven across active nodes.",
                    "Slightly Uneven",
                    "Monitor trend and consider rebalancing if unevenness persists."
            );
        }

        return new HealthAssessment(
                "Critical",
                "red",
                "Data distribution is significantly uneven across active nodes.",
                "Uneven",
                "Plan rebalance actions and verify routing or failed-node recovery."
        );
    }

    private double coefficientOfVariation(List<NodeCount> counts, long averagePerNode) {
        if (counts.isEmpty() || averagePerNode == 0) {
            return 0.0;
        }

        double variance = counts.stream()
                .mapToDouble(node -> {
                    double diff = node.count() - averagePerNode;
                    return diff * diff;
                })
                .average()
                .orElse(0.0);

        double stddev = Math.sqrt(variance);
        return stddev / averagePerNode;
    }

    private double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private record NodeCount(String nodeId, long count) {
    }

    private record HealthAssessment(
            String clusterHealth,
            String healthColor,
            String oneLineSummary,
            String balanceStatus,
            String recommendedAction
    ) {
    }
}
