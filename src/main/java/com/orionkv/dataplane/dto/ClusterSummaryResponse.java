package com.orionkv.dataplane.dto;

import java.time.Instant;
import java.util.List;

public record ClusterSummaryResponse(
        Instant generatedAt,
        String clusterHealth,
        String healthColor,
        String oneLineSummary,
        QuickFacts quickFacts,
        Balance balance,
        Distribution distribution,
        List<String> whatThisMeans,
        String recommendedAction
) {

    public record QuickFacts(
            int activeNodes,
            int nodesResponded,
            long totalRecords,
            long averagePerNode
    ) {
    }

    public record Balance(
            String status,
            String mostLoadedNode,
            String leastLoadedNode,
            long mostLoadedCount,
            long leastLoadedCount,
            long gapRecords,
            double gapPercentVsAverage
    ) {
    }

    public record Distribution(
            int nodesAboveNormalRange,
            int nodesBelowNormalRange,
            String normalRangeDefinition
    ) {
    }
}
