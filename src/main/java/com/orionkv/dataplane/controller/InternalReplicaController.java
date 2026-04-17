package com.orionkv.dataplane.controller;

import com.orionkv.dataplane.dto.ReplicaBatchApplyRequest;
import com.orionkv.dataplane.dto.ReplicaBatchApplyResponse;
import com.orionkv.dataplane.dto.ReplicaReadResponse;
import com.orionkv.dataplane.dto.ReplicaStreamRangeRequest;
import com.orionkv.dataplane.dto.ReplicaStreamRangeResponse;
import com.orionkv.dataplane.dto.ReplicaStreamResponse;
import com.orionkv.dataplane.dto.ReplicaWriteRequest;
import com.orionkv.dataplane.dto.ReplicaWriteResponse;
import com.orionkv.dataplane.model.StoredValue;
import com.orionkv.dataplane.service.ReplicaApplyResult;
import com.orionkv.dataplane.service.StorageService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class InternalReplicaController {

    private final StorageService storageService;

    public InternalReplicaController(StorageService storageService) {
        this.storageService = storageService;
    }

    @PostMapping({"/internal/replica/put", "/internal/replica_put"})
    @ResponseStatus(HttpStatus.OK)
    public ReplicaWriteResponse applyReplicaPut(@Valid @RequestBody ReplicaWriteRequest request) {
        ReplicaApplyResult result = storageService.applyReplicaWrite(request.toReplicaRecord());
        return result.applied()
                ? ReplicaWriteResponse.applied(result.storedValue())
                : ReplicaWriteResponse.ignored(result.storedValue());
    }

    @GetMapping({"/internal/replica/get", "/internal/replica_get"})
    @ResponseStatus(HttpStatus.OK)
    public ReplicaReadResponse getReplica(@RequestParam String key) {
        return storageService.getVersioned(key)
                .map(ReplicaReadResponse::from)
                .orElseGet(() -> ReplicaReadResponse.missing(key));
    }

    @GetMapping("/internal/replica/stream")
    @ResponseStatus(HttpStatus.OK)
    public ReplicaStreamResponse streamRange(@RequestParam long startToken, @RequestParam long endToken) {
        List<StoredValue> values = storageService.scanRange(startToken, endToken);
        return ReplicaStreamResponse.from(startToken, endToken, values);
    }

    @PostMapping("/internal/stream_range")
    @ResponseStatus(HttpStatus.OK)
    public ReplicaStreamRangeResponse streamRange(@Valid @RequestBody ReplicaStreamRangeRequest request) {
        return ReplicaStreamRangeResponse.from(
                storageService.scanRangePage(
                        request.startToken(),
                        request.endToken(),
                        request.resolvedBatchSize(),
                        request.cursor()
                )
        );
    }

    @PostMapping("/internal/replica/apply-batch")
    @ResponseStatus(HttpStatus.OK)
    public ReplicaBatchApplyResponse applyBatch(@Valid @RequestBody ReplicaBatchApplyRequest request) {
        return ReplicaBatchApplyResponse.from(
                storageService.applyReplicaBatch(request.records().stream().map(ReplicaWriteRequest::toReplicaRecord).toList())
        );
    }
}
