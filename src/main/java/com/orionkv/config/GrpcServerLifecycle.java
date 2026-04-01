package com.orionkv.config;

import com.orionkv.controlplane.bootstrap.rpc.ClusterRpcHandler;
import com.orionkv.controlplane.bootstrap.service.JoinService;
import com.orionkv.controlplane.membership.rpc.GossipRpcHandler;
import com.orionkv.controlplane.membership.service.MembershipService;
import com.orionkv.controlplane.ring.service.HashRingService;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class GrpcServerLifecycle implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GrpcServerLifecycle.class);

    private final NodeProperties nodeProperties;
    private final MembershipService membershipService;
    private final HashRingService hashRingService;
    private final JoinService joinService;
    private final GossipRpcHandler gossipRpcHandler;
    private final ClusterRpcHandler clusterRpcHandler;
    private Server server;

    public GrpcServerLifecycle(
            NodeProperties nodeProperties,
            MembershipService membershipService,
            HashRingService hashRingService,
            JoinService joinService,
            GossipRpcHandler gossipRpcHandler,
            ClusterRpcHandler clusterRpcHandler
    ) {
        this.nodeProperties = nodeProperties;
        this.membershipService = membershipService;
        this.hashRingService = hashRingService;
        this.joinService = joinService;
        this.gossipRpcHandler = gossipRpcHandler;
        this.clusterRpcHandler = clusterRpcHandler;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (nodeProperties.getNodeId() == null || nodeProperties.getNodeId().isBlank()
                || nodeProperties.getAddress() == null || nodeProperties.getAddress().isBlank()) {
            log.info("Skipping gRPC control-plane startup because node.node-id or node.address is not configured");
            return;
        }

        membershipService.updateHeartbeat(nodeProperties.getNodeId(), nodeProperties.getAddress(), 0);
        hashRingService.rebuildRing(membershipService.getMembershipSnapshot());

        server = ServerBuilder.forPort(nodeProperties.getPort())
                .addService(gossipRpcHandler)
                .addService(clusterRpcHandler)
                .build()
                .start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (server != null) {
                server.shutdown();
            }
        }));

        if (nodeProperties.getSeedAddress() != null && !nodeProperties.getSeedAddress().isBlank()) {
            joinService.joinCluster(nodeProperties.getSeedAddress());
        }
    }
}
