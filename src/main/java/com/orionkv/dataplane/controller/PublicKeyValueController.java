package com.orionkv.dataplane.controller;

import com.orionkv.dataplane.dto.DeleteResponse;
import com.orionkv.dataplane.dto.KeyValueResponse;
import com.orionkv.dataplane.dto.PutValueRequest;
import com.orionkv.dataplane.model.StoredValue;
import com.orionkv.dataplane.service.StorageService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/kv")
public class PublicKeyValueController {

    private final StorageService storageService;

    public PublicKeyValueController(StorageService storageService) {
        this.storageService = storageService;
    }

    @PutMapping("/{key}")
    @ResponseStatus(HttpStatus.OK)
    public KeyValueResponse put(@PathVariable String key, @Valid @RequestBody PutValueRequest request) {
        StoredValue storedValue = storageService.put(key, request.value(), request.timestamp());
        return KeyValueResponse.from(storedValue);
    }

    @GetMapping("/{key}")
    @ResponseStatus(HttpStatus.OK)
    public KeyValueResponse get(@PathVariable String key) {
        return KeyValueResponse.from(storageService.get(key));
    }

    @DeleteMapping("/{key}")
    @ResponseStatus(HttpStatus.OK)
    public DeleteResponse delete(@PathVariable String key, @RequestParam(required = false) Long timestamp) {
        long deleteTimestamp = timestamp != null ? timestamp : System.currentTimeMillis();
        storageService.delete(key, deleteTimestamp);
        return new DeleteResponse(key, deleteTimestamp, "deleted");
    }
}
