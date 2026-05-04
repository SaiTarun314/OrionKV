package com.orionkv.clientrouter.controller;

import com.orionkv.clientrouter.dto.DeleteValueRequest;
import com.orionkv.clientrouter.dto.ClientRestartRequest;
import com.orionkv.clientrouter.dto.ClientRestartResponse;
import com.orionkv.clientrouter.dto.JoinConfirmationRequest;
import com.orionkv.clientrouter.dto.JoinConfirmationResponse;
import com.orionkv.clientrouter.dto.JoinSeedResponse;
import com.orionkv.clientrouter.dto.NodeRegistrationRequest;
import com.orionkv.clientrouter.dto.PutValueRequest;
import com.orionkv.clientrouter.dto.RefreshNodesRequest;
import com.orionkv.clientrouter.dto.RoutedDeleteResponse;
import com.orionkv.clientrouter.dto.RoutedGetResponse;
import com.orionkv.clientrouter.dto.RoutedPutResponse;
import com.orionkv.clientrouter.model.RouterRegistrySnapshot;
import com.orionkv.clientrouter.model.RoutedRequestResult;
import com.orionkv.clientrouter.service.JoinRoutingService;
import com.orionkv.clientrouter.service.KvRoutingService;
import com.orionkv.clientrouter.service.ClientLifecycleService;
import com.orionkv.clientrouter.service.NodeRegistryService;
import com.orionkv.proto.ClientDeleteResponse;
import com.orionkv.proto.ClientGetResponse;
import com.orionkv.proto.ClientPutResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/client")
public class ClientRouterController {

    private final NodeRegistryService nodeRegistryService;
    private final JoinRoutingService joinRoutingService;
    private final KvRoutingService kvRoutingService;
    private final ClientLifecycleService clientLifecycleService;

    public ClientRouterController(
            NodeRegistryService nodeRegistryService,
            JoinRoutingService joinRoutingService,
            KvRoutingService kvRoutingService,
            ClientLifecycleService clientLifecycleService
    ) {
        this.nodeRegistryService = nodeRegistryService;
        this.joinRoutingService = joinRoutingService;
        this.kvRoutingService = kvRoutingService;
        this.clientLifecycleService = clientLifecycleService;
    }

    @GetMapping("/nodes")
    public RouterRegistrySnapshot nodes() {
        return nodeRegistryService.snapshot();
    }

    @PostMapping("/nodes/register")
    public RouterRegistrySnapshot register(@Valid @RequestBody NodeRegistrationRequest request) {
        nodeRegistryService.upsertManualNode(request.getNodeId(), request.getGrpcAddress());
        return nodeRegistryService.snapshot();
    }

    @PostMapping("/nodes/seed")
    public JoinSeedResponse resolveJoinSeed(@Valid @RequestBody NodeRegistrationRequest request) {
        return joinRoutingService.resolveSeed(request);
    }

    @PostMapping("/nodes/confirm")
    public JoinConfirmationResponse confirmJoin(@RequestBody JoinConfirmationRequest request) {
        return joinRoutingService.confirmJoin(
                request.getJoiningNodeId(),
                request.getSeedGrpcAddress(),
                request.getPollAttempts(),
                request.getPollDelayMs()
        );
    }

    @PostMapping("/nodes/refresh")
    public RouterRegistrySnapshot refresh(@RequestBody(required = false) RefreshNodesRequest request) {
        String preferredSeed = request == null ? null : request.getSeedGrpcAddress();
        return joinRoutingService.refreshFromCluster(preferredSeed);
    }

    @PostMapping("/admin/restart")
    public ClientRestartResponse restart(@RequestBody(required = false) ClientRestartRequest request) {
        boolean clearRegistry = request != null && request.isClearRegistry();
        long delayMs = request == null ? 250L : request.getDelayMs();
        clientLifecycleService.scheduleRestart(clearRegistry, delayMs);
        return new ClientRestartResponse(
                true,
                clearRegistry,
                Math.max(100L, delayMs),
                "Client router shutdown scheduled. For automatic restart in Docker, run the container with a restart policy such as --restart unless-stopped."
        );
    }

    @PutMapping("/kv/{key}")
    public RoutedPutResponse put(@PathVariable String key, @Valid @RequestBody PutValueRequest request) {
        RoutedRequestResult<ClientPutResponse> routed = kvRoutingService.put(key, request.getValue(), request.getTimestamp());
        ClientPutResponse response = routed.response();
        return new RoutedPutResponse(
                response.getSuccess(),
                response.getKey(),
                response.getToken(),
                response.getTimestamp(),
                response.getAckCount(),
                response.getRequiredAcks(),
                response.getReplicaNodeIdsList(),
                response.getMessage(),
                routed.contactedNodeId(),
                routed.contactedGrpcAddress()
        );
    }

    @GetMapping("/kv/{key}")
    public RoutedGetResponse get(@PathVariable String key) {
        RoutedRequestResult<ClientGetResponse> routed = kvRoutingService.get(key);
        ClientGetResponse response = routed.response();
        return new RoutedGetResponse(
                response.getFound(),
                response.getKey(),
                response.getValue(),
                response.getToken(),
                response.getTimestamp(),
                response.getTombstone(),
                response.getResponseCount(),
                response.getRequiredResponses(),
                response.getReplicaNodeIdsList(),
                response.getMessage(),
                routed.contactedNodeId(),
                routed.contactedGrpcAddress()
        );
    }

    @DeleteMapping("/kv/{key}")
    public RoutedDeleteResponse delete(@PathVariable String key, @RequestBody(required = false) DeleteValueRequest request) {
        Long timestamp = request == null ? null : request.getTimestamp();
        RoutedRequestResult<ClientDeleteResponse> routed = kvRoutingService.delete(key, timestamp);
        ClientDeleteResponse response = routed.response();
        return new RoutedDeleteResponse(
                response.getSuccess(),
                response.getKey(),
                response.getToken(),
                response.getTimestamp(),
                response.getAckCount(),
                response.getRequiredAcks(),
                response.getReplicaNodeIdsList(),
                response.getMessage(),
                routed.contactedNodeId(),
                routed.contactedGrpcAddress()
        );
    }
}
