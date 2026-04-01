package com.orionkv.config;

import com.orionkv.api.ClientGrpcService;
import com.orionkv.coordination.CoordinatorService;
import com.orionkv.coordination.DefaultCoordinatorService;
import com.orionkv.coordination.QuorumConfig;
import com.orionkv.replica.NoopReplicaClient;
import com.orionkv.replica.ReplicaClient;
import com.orionkv.ring.ConsistentHashRing;
import com.orionkv.ring.HashFunction;
import com.orionkv.ring.Murmur3HashFunction;
import com.orionkv.ring.ReplicaSelector;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ClusterProperties.class)
public class OrionKvConfiguration {

    @Bean
    public HashFunction hashFunction() {
        return new Murmur3HashFunction();
    }

    @Bean
    public ReplicaSelector replicaSelector(HashFunction hashFunction, ClusterProperties properties) {
        return new ConsistentHashRing(hashFunction, properties.toNodeInfos(), properties.getVirtualNodesPerPhysical());
    }

    @Bean
    public QuorumConfig quorumConfig(ClusterProperties properties) {
        ClusterProperties.Quorum q = properties.getQuorum();
        return new QuorumConfig(q.getN(), q.getR(), q.getW());
    }

    @Bean
    public ReplicaClient replicaClient() {
        return new NoopReplicaClient();
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public CoordinatorService coordinatorService(
            ClusterProperties properties,
            QuorumConfig quorumConfig,
            ReplicaSelector replicaSelector,
            ReplicaClient replicaClient,
            Clock clock) {
        return new DefaultCoordinatorService(
                properties.getLocalNodeId(),
                quorumConfig,
                replicaSelector,
                replicaClient,
                clock);
    }

    @Bean
    public ClientGrpcService clientGrpcService(
            ClusterProperties properties,
            QuorumConfig quorumConfig,
            CoordinatorService coordinatorService) {
        return new ClientGrpcService(
                properties.getLocalNodeId(),
                quorumConfig.w(),
                quorumConfig.r(),
                coordinatorService);
    }
}
