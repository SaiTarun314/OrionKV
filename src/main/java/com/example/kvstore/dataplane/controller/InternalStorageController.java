package com.example.kvstore.dataplane.controller;

import com.example.kvstore.dataplane.dto.RangeScanResponse;
import com.example.kvstore.dataplane.service.StorageService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/storage")
public class InternalStorageController {

    private final StorageService storageService;

    public InternalStorageController(StorageService storageService) {
        this.storageService = storageService;
    }

    @GetMapping("/range")
    @ResponseStatus(HttpStatus.OK)
    public RangeScanResponse scanRange(@RequestParam long startToken, @RequestParam long endToken) {
        return RangeScanResponse.from(startToken, endToken, storageService.scanRange(startToken, endToken));
    }
}
