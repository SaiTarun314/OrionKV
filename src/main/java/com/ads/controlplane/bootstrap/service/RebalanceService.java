package com.ads.controlplane.bootstrap.service;

import com.ads.controlplane.ring.model.TokenRange;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class RebalanceService {

    public List<TokenRange> detectNewRangesForNode(
            List<TokenRange> previousRanges,
            List<TokenRange> currentRanges,
            String nodeId
    ) {
        Set<TokenRange> previousOwned = previousRanges == null
                ? Set.of()
                : previousRanges.stream()
                .filter(range -> nodeId.equals(range.ownerNodeId()))
                .collect(Collectors.toSet());

        return currentRanges == null
                ? List.of()
                : currentRanges.stream()
                .filter(range -> nodeId.equals(range.ownerNodeId()))
                .filter(range -> !previousOwned.contains(range))
                .toList();
    }
}
