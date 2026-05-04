package com.orionkv.dataplane.controller;

import com.orionkv.coordinationplane.service.ClusterSummaryService;
import com.orionkv.controlplane.bootstrap.model.RebalanceTimingSnapshot;
import com.orionkv.controlplane.bootstrap.service.RebalanceMetricsRegistry;
import com.orionkv.dataplane.dto.ClusterSummaryResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/cluster")
public class InternalClusterController {

    private final ClusterSummaryService clusterSummaryService;

    public InternalClusterController(ClusterSummaryService clusterSummaryService) {
        this.clusterSummaryService = clusterSummaryService;
    }

    @GetMapping("/summary")
    @ResponseStatus(HttpStatus.OK)
    public ClusterSummaryResponse getSummary() {
        return clusterSummaryService.buildSummary();
    }

    @GetMapping("/rebalance/latest")
    @ResponseStatus(HttpStatus.OK)
    public RebalanceTimingSnapshot latestRebalanceTiming() {
        return RebalanceMetricsRegistry.latest();
    }
}
