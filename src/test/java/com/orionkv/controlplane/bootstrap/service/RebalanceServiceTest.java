package com.orionkv.controlplane.bootstrap.service;

import com.orionkv.controlplane.bootstrap.model.PrimaryOwnershipMovement;
import com.orionkv.controlplane.ring.model.TokenRange;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RebalanceServiceTest {

    @Test
    void shouldComputePrimaryOwnershipMovementDiff() {
        RebalanceService rebalanceService = new RebalanceService();

        List<TokenRange> previousRanges = List.of(
                new TokenRange(0L, 10L, "node-a"),
                new TokenRange(10L, 20L, "node-b"),
                new TokenRange(20L, 30L, "node-a")
        );
        List<TokenRange> currentRanges = List.of(
                new TokenRange(0L, 10L, "node-a"),
                new TokenRange(10L, 20L, "node-c"),
                new TokenRange(20L, 30L, "node-a")
        );

        List<PrimaryOwnershipMovement> movementPlans =
                rebalanceService.computePrimaryOwnershipDiff(previousRanges, currentRanges);

        assertThat(movementPlans).containsExactly(
                new PrimaryOwnershipMovement(10L, 20L, "node-b", "node-c")
        );
        assertThat(rebalanceService.movementsForTargetNode(movementPlans, "node-c"))
                .containsExactly(new PrimaryOwnershipMovement(10L, 20L, "node-b", "node-c"));
    }

    @Test
    void shouldDetectNewReplicaRangesForNode() {
        RebalanceService rebalanceService = new RebalanceService();

        List<TokenRange> previousRanges = List.of(
                new TokenRange(0L, 10L, "node-a"),
                new TokenRange(10L, 20L, "node-a")
        );
        List<TokenRange> currentRanges = List.of(
                new TokenRange(0L, 10L, "node-a"),
                new TokenRange(10L, 20L, "node-a"),
                new TokenRange(20L, 30L, "node-a")
        );

        assertThat(rebalanceService.detectNewRangesForNode(previousRanges, currentRanges, "node-a"))
                .containsExactly(new TokenRange(20L, 30L, "node-a"));
    }
}
