package com.orionkv.dataplane;

import com.orionkv.dataplane.service.StorageService;
import com.orionkv.dataplane.util.TokenUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ReplicaStreamIntegrationTest {

    private static final String WAL_PATH = "target/test-data/replica-stream-" + UUID.randomUUID() + ".log";

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("dataplane.storage.log-path", () -> WAL_PATH);
        registry.add("node.node-id", () -> "test-node");
        registry.add("node.address", () -> "127.0.0.1:0");
    }

    @Autowired
    private StorageService storageService;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void replicaStreamReturnsCorrectRecordsForWrapAroundRange() throws Exception {
        List<KeyTokenPair> keys = findDistinctKeys(3);
        KeyTokenPair low = keys.get(0);
        KeyTokenPair middle = keys.get(1);
        KeyTokenPair high = keys.get(2);

        storageService.put(low.key(), "low-value", 100L);
        storageService.put(middle.key(), "middle-value", 100L);
        storageService.put(high.key(), "high-value", 100L);

        mockMvc.perform(get("/internal/replica/stream")
                        .queryParam("startToken", Long.toString(high.token()))
                        .queryParam("endToken", Long.toString(low.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.records[0].key").value(high.key()))
                .andExpect(jsonPath("$.records[1].key").value(low.key()));
    }

    private List<KeyTokenPair> findDistinctKeys(int count) {
        List<KeyTokenPair> candidates = new ArrayList<>();

        for (int index = 0; candidates.size() < count; index++) {
            String key = "replica-stream-key-" + index;
            long token = TokenUtil.tokenFor(key);

            boolean exists = candidates.stream().anyMatch(candidate -> candidate.token() == token);
            if (!exists) {
                candidates.add(new KeyTokenPair(key, token));
            }
        }

        return candidates.stream()
                .sorted(Comparator.comparingLong(KeyTokenPair::token))
                .toList();
    }

    private record KeyTokenPair(String key, long token) {
    }
}
