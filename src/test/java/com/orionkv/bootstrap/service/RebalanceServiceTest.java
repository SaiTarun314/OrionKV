package com.orionkv.bootstrap.service;

import com.orionkv.ring.model.TokenRange;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RebalanceServiceTest {

    @Test
    void shouldReturnOnlyNewRangesAssignedToNode() {
        RebalanceService rebalanceService = new RebalanceService();

        List<TokenRange> previousRanges = List.of(
                new TokenRange(10L, 20L, "node-a")
        );
        List<TokenRange> currentRanges = List.of(
                new TokenRange(10L, 20L, "node-a"),
                new TokenRange(20L, 30L, "node-a"),
                new TokenRange(30L, 40L, "node-b")
        );

        List<TokenRange> newRanges = rebalanceService.detectNewRangesForNode(previousRanges, currentRanges, "node-a");

        assertThat(newRanges).containsExactly(new TokenRange(20L, 30L, "node-a"));
    }
}
