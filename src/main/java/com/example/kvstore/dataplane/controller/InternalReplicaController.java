package com.example.kvstore.dataplane.controller;

import com.example.kvstore.dataplane.dto.ReplicaBatchApplyRequest;
import com.example.kvstore.dataplane.dto.ReplicaBatchApplyResponse;
import com.example.kvstore.dataplane.dto.ReplicaStreamResponse;
import com.example.kvstore.dataplane.dto.ReplicaWriteRequest;
import com.example.kvstore.dataplane.dto.ReplicaWriteResponse;
import com.example.kvstore.dataplane.model.StoredValue;
import com.example.kvstore.dataplane.service.ReplicaApplyResult;
import com.example.kvstore.dataplane.service.StorageService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/internal/replica")
public class InternalReplicaController {

    private final StorageService storageService;

    public InternalReplicaController(StorageService storageService) {
        this.storageService = storageService;
    }

    @PostMapping("/put")
    @ResponseStatus(HttpStatus.OK)
    public ReplicaWriteResponse applyReplicaPut(@Valid @RequestBody ReplicaWriteRequest request) {
        ReplicaApplyResult result = storageService.applyReplicaWrite(request.toReplicaRecord());
        return result.applied()
            ? ReplicaWriteResponse.applied(result.storedValue())
            : ReplicaWriteResponse.ignored(result.storedValue());
    }

    @GetMapping("/stream")
    @ResponseStatus(HttpStatus.OK)
    public ReplicaStreamResponse streamRange(@RequestParam long startToken, @RequestParam long endToken) {
        List<StoredValue> values = storageService.scanRange(startToken, endToken);
        return ReplicaStreamResponse.from(startToken, endToken, values);
    }

    @PostMapping("/apply-batch")
    @ResponseStatus(HttpStatus.OK)
    public ReplicaBatchApplyResponse applyBatch(@Valid @RequestBody ReplicaBatchApplyRequest request) {
        return ReplicaBatchApplyResponse.from(
            storageService.applyReplicaBatch(request.records().stream().map(ReplicaWriteRequest::toReplicaRecord).toList())
        );
    }
}
