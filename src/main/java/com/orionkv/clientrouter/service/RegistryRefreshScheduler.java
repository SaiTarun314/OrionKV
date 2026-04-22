package com.orionkv.clientrouter.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RegistryRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(RegistryRefreshScheduler.class);

    private final JoinRoutingService joinRoutingService;
    private final NodeRegistryService nodeRegistryService;

    public RegistryRefreshScheduler(JoinRoutingService joinRoutingService, NodeRegistryService nodeRegistryService) {
        this.joinRoutingService = joinRoutingService;
        this.nodeRegistryService = nodeRegistryService;
    }

    @Scheduled(fixedDelayString = "${client.router.refresh-interval-ms:5000}")
    public void refreshMembership() {
        if (!nodeRegistryService.hasAliveNodes()) {
            return;
        }

        try {
            joinRoutingService.refreshFromCluster(null);
        } catch (RuntimeException ex) {
            log.warn("Client router membership refresh failed: {}", ex.getMessage());
        }
    }
}
