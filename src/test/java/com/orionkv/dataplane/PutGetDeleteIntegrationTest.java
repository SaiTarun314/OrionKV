package com.orionkv.dataplane;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PutGetDeleteIntegrationTest {

    private static final String WAL_PATH = "target/test-data/api-" + UUID.randomUUID() + ".log";

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("dataplane.storage.log-path", () -> WAL_PATH);
        registry.add("node.node-id", () -> "test-node");
        registry.add("node.address", () -> "127.0.0.1:0");
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void putGetDeleteFlowUsesTombstonesAndReturns404AfterDelete() throws Exception {
        mockMvc.perform(put("/api/kv/test-key")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "value": "value-1",
                                  "timestamp": 1000
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("test-key"))
                .andExpect(jsonPath("$.value").value("value-1"))
                .andExpect(jsonPath("$.tombstone").value(false));

        mockMvc.perform(get("/api/kv/test-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("test-key"))
                .andExpect(jsonPath("$.value").value("value-1"))
                .andExpect(jsonPath("$.timestamp").value(1000));

        mockMvc.perform(delete("/api/kv/test-key").queryParam("timestamp", "2000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("test-key"))
                .andExpect(jsonPath("$.status").value("deleted"));

        mockMvc.perform(get("/api/kv/test-key"))
                .andExpect(status().isNotFound());
    }
}
