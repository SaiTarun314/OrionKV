package com.orionkv.controlplane.bootstrap.service;

import com.orionkv.controlplane.bootstrap.model.PrimaryOwnershipMovement;
import com.orionkv.controlplane.ring.model.TokenRange;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RebalanceService {

    public List<PrimaryOwnershipMovement> computePrimaryOwnershipDiff(
            List<TokenRange> oldPrimaryRanges,
            List<TokenRange> newPrimaryRanges
    ) {
        java.util.SortedSet<Long> boundaries = new java.util.TreeSet<>();
        if (oldPrimaryRanges != null) {
            oldPrimaryRanges.stream().map(TokenRange::endInclusive).forEach(boundaries::add);
        }
        if (newPrimaryRanges != null) {
            newPrimaryRanges.stream().map(TokenRange::endInclusive).forEach(boundaries::add);
        }

        if (boundaries.isEmpty()) {
            return List.of();
        }

        List<Long> orderedBoundaries = new java.util.ArrayList<>(boundaries);
        List<PrimaryOwnershipMovement> movements = new java.util.ArrayList<>();

        for (int index = 0; index < orderedBoundaries.size(); index++) {
            long endToken = orderedBoundaries.get(index);
            long startToken = index == 0
                    ? orderedBoundaries.get(orderedBoundaries.size() - 1)
                    : orderedBoundaries.get(index - 1);

            String oldOwner = findOwnerForToken(oldPrimaryRanges, endToken);
            String newOwner = findOwnerForToken(newPrimaryRanges, endToken);
            if (oldOwner == null || newOwner == null || oldOwner.equals(newOwner)) {
                continue;
            }

            movements.add(new PrimaryOwnershipMovement(startToken, endToken, oldOwner, newOwner));
        }

        return movements;
    }

    public List<PrimaryOwnershipMovement> movementsForTargetNode(
            List<PrimaryOwnershipMovement> movementPlans,
            String nodeId
    ) {
        return movementPlans == null
                ? List.of()
                : movementPlans.stream()
                .filter(plan -> nodeId.equals(plan.targetNodeId()))
                .toList();
    }

    private String findOwnerForToken(List<TokenRange> ranges, long token) {
        if (ranges == null || ranges.isEmpty()) {
            return null;
        }

        return ranges.stream()
                .filter(range -> containsToken(range, token))
                .map(TokenRange::ownerNodeId)
                .findFirst()
                .orElse(null);
    }

    private boolean containsToken(TokenRange range, long token) {
        if (range == null) {
            return false;
        }

        if (range.startExclusive() < range.endInclusive()) {
            return token > range.startExclusive() && token <= range.endInclusive();
        }
        if (range.startExclusive() > range.endInclusive()) {
            return token > range.startExclusive() || token <= range.endInclusive();
        }
        return true;
    }
}
