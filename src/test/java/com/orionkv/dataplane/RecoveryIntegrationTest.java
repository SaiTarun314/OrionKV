package com.orionkv.dataplane;

import com.orionkv.NodeApplication;
import com.orionkv.dataplane.model.StoredValue;
import com.orionkv.dataplane.service.StorageService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecoveryIntegrationTest {

    @Test
    void persistedEntriesAreRecoveredAfterApplicationRestart() {
        Path walPath = Path.of("target/test-data/recovery-" + System.nanoTime() + ".log");

        try (ConfigurableApplicationContext firstContext = startContext(walPath)) {
            StorageService storageService = firstContext.getBean(StorageService.class);
            storageService.put("recovered-key", "persisted-value", 1234L);
        }

        try (ConfigurableApplicationContext secondContext = startContext(walPath)) {
            StorageService storageService = secondContext.getBean(StorageService.class);
            StoredValue recoveredValue = storageService.get("recovered-key");

            assertEquals("recovered-key", recoveredValue.key());
            assertEquals("persisted-value", recoveredValue.value());
            assertEquals(1234L, recoveredValue.timestamp());
        }
    }

    private ConfigurableApplicationContext startContext(Path walPath) {
        return new SpringApplicationBuilder(NodeApplication.class)
                .properties(
                        "server.port=0",
                        "node.node-id=recovery-node",
                        "node.address=127.0.0.1:0",
                        "dataplane.storage.log-path=" + walPath
                )
                .run();
    }
}
