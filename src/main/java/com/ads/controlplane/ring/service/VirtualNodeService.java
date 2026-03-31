package com.ads.controlplane.ring.service;

import com.ads.controlplane.common.util.HashUtil;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class VirtualNodeService {

    public Map<Long, String> generateTokens(String nodeId, int virtualNodeCount) {
        Map<Long, String> tokens = new LinkedHashMap<>();
        for (int i = 0; i < virtualNodeCount; i++) {
            tokens.put(HashUtil.hash(nodeId + i), nodeId);
        }
        return tokens;
    }
}
