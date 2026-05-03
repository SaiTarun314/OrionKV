package com.orionkv.clientrouter.service;

import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;

@Service
public class ClientLifecycleService {

    private final ConfigurableApplicationContext applicationContext;
    private final NodeRegistryService nodeRegistryService;

    public ClientLifecycleService(
            ConfigurableApplicationContext applicationContext,
            NodeRegistryService nodeRegistryService
    ) {
        this.applicationContext = applicationContext;
        this.nodeRegistryService = nodeRegistryService;
    }

    public void scheduleRestart(boolean clearRegistry, long delayMs) {
        long boundedDelayMs = Math.max(100L, delayMs);

        if (clearRegistry) {
            nodeRegistryService.resetLocalState(true);
        }

        Thread shutdownThread = new Thread(() -> {
            try {
                Thread.sleep(boundedDelayMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }

            int exitCode = SpringApplication.exit(applicationContext, () -> 0);
            System.exit(exitCode);
        }, "client-router-restart");
        shutdownThread.setDaemon(false);
        shutdownThread.start();
    }
}
