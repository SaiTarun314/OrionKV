package com.orionkv.controlplane.bootstrap.service;

import com.orionkv.config.NodeProperties;
import com.orionkv.controlplane.membership.model.MemberRecord;
import com.orionkv.controlplane.membership.model.MemberStatus;
import com.orionkv.controlplane.membership.service.MembershipService;
import com.orionkv.controlplane.ring.model.TokenRange;
import com.orionkv.controlplane.ring.service.HashRingService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class FailureRebalanceService {

    private final MembershipService membershipService;
    private final HashRingService hashRingService;
    private final RebalanceService rebalanceService;
    private final BootstrapTransferService bootstrapTransferService;
    private final NodeProperties nodeProperties;
    private final ConcurrentHashMap<String, Long> handledDeadIncarnations = new ConcurrentHashMap<>();

    public FailureRebalanceService(
            MembershipService membershipService,
            HashRingService hashRingService,
            RebalanceService rebalanceService,
            BootstrapTransferService bootstrapTransferService,
            NodeProperties nodeProperties
    ) {
        this.membershipService = membershipService;
        this.hashRingService = hashRingService;
        this.rebalanceService = rebalanceService;
        this.bootstrapTransferService = bootstrapTransferService;
        this.nodeProperties = nodeProperties;
    }

    @Scheduled(
            fixedDelayString = "${node.failure-detection-interval-ms:2000}",
            initialDelayString = "${node.failure-detection-interval-ms:2000}"
    )
    public void rebalanceDeadMembers() {
        for (MemberRecord deadMember : membershipService.getMembershipSnapshot()) {
            if (deadMember.status() != MemberStatus.DEAD) {
                continue;
            }
            if (deadMember.nodeId().equals(nodeProperties.getNodeId())) {
                continue;
            }

            Long handledIncarnation = handledDeadIncarnations.get(deadMember.nodeId());
            if (handledIncarnation != null && handledIncarnation >= deadMember.incarnation()) {
                continue;
            }

            rebalanceDeadMember(deadMember);
            handledDeadIncarnations.put(deadMember.nodeId(), deadMember.incarnation());
        }
    }

    void rebalanceDeadMember(MemberRecord deadMember) {
        List<MemberRecord> currentMembership = membershipService.getMembershipSnapshot().stream().toList();
        List<MemberRecord> previousMembership = currentMembership.stream()
                .map(member -> member.nodeId().equals(deadMember.nodeId())
                        ? new MemberRecord(
                                member.nodeId(),
                                member.address(),
                                MemberStatus.ALIVE,
                                member.incarnation(),
                                member.lastSeen()
                        )
                        : member)
                .toList();

        hashRingService.rebuildRing(previousMembership);
        List<TokenRange> previousReplicaRanges = hashRingService.getReplicaTokenRanges(nodeProperties.getNodeId());

        hashRingService.rebuildRing(currentMembership);
        List<TokenRange> currentReplicaRanges = hashRingService.getReplicaTokenRanges(nodeProperties.getNodeId());

        List<TokenRange> newRanges = rebalanceService.detectNewRangesForNode(
                previousReplicaRanges,
                currentReplicaRanges,
                nodeProperties.getNodeId()
        );

        if (newRanges.isEmpty()) {
            return;
        }

        Map<TokenRange, String> donorAddresses = donorAddressesForRanges(previousMembership, currentMembership, newRanges, deadMember);
        hashRingService.rebuildRing(currentMembership);
        bootstrapTransferService.transferNewRanges(newRanges, donorAddresses, nodeProperties.getNodeId());
    }

    private Map<TokenRange, String> donorAddressesForRanges(
            List<MemberRecord> previousMembership,
            List<MemberRecord> currentMembership,
            List<TokenRange> newRanges,
            MemberRecord deadMember
    ) {
        Map<String, MemberRecord> currentMembersByNodeId = currentMembership.stream()
                .collect(java.util.stream.Collectors.toMap(MemberRecord::nodeId, member -> member, (left, right) -> left));

        hashRingService.rebuildRing(previousMembership);
        Map<TokenRange, String> donorAddresses = newRanges.stream()
                .collect(java.util.stream.Collectors.toMap(
                        range -> range,
                        range -> hashRingService.findReplicasForToken(range.endInclusive()).replicaNodeIds().stream()
                                .filter(candidateNodeId -> !candidateNodeId.equals(deadMember.nodeId()))
                                .filter(candidateNodeId -> !candidateNodeId.equals(nodeProperties.getNodeId()))
                                .map(currentMembersByNodeId::get)
                                .filter(candidate -> candidate != null && candidate.status() == MemberStatus.ALIVE)
                                .map(MemberRecord::address)
                                .findFirst()
                                .orElse(null)
                ));
        hashRingService.rebuildRing(currentMembership);
        return donorAddresses;
    }
}
