package com.orionkv.dataplane.service;

import com.orionkv.dataplane.dto.ReplicaWriteRequest;
import com.orionkv.dataplane.model.StoredValue;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HttpReplicaRepairClient implements ReplicaRepairClient {

    private final RestClient restClient = RestClient.create();

    @Override
    public void repair(String replicaBaseUrl, StoredValue latestVersion) {
        if (replicaBaseUrl == null || replicaBaseUrl.isBlank()) {
            return;
        }

        String normalizedBaseUrl = replicaBaseUrl.endsWith("/")
                ? replicaBaseUrl.substring(0, replicaBaseUrl.length() - 1)
                : replicaBaseUrl;

        restClient.post()
                .uri(normalizedBaseUrl + "/internal/replica_put")
                .body(new ReplicaWriteRequest(
                        latestVersion.key(),
                        latestVersion.value(),
                        latestVersion.timestamp(),
                        latestVersion.tombstone(),
                        latestVersion.token(),
                        null
                ))
                .retrieve()
                .toBodilessEntity();
    }
}
