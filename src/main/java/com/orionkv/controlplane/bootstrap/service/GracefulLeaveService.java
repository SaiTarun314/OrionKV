package com.orionkv.controlplane.bootstrap.service;

import com.orionkv.config.NodeProperties;
import com.orionkv.controlplane.membership.model.MemberRecord;
import com.orionkv.controlplane.membership.model.MemberStatus;
import com.orionkv.controlplane.membership.service.MembershipService;
import com.orionkv.controlplane.ring.model.TokenRange;
import com.orionkv.controlplane.ring.service.HashRingService;
import com.orionkv.coordinationplane.rpc.ReplicaDataClient;
import com.orionkv.dataplane.model.ReplicaRecord;
import com.orionkv.dataplane.model.StoredValue;
import com.orionkv.dataplane.service.ReplicaStreamPage;
import com.orionkv.dataplane.service.StorageService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class GracefulLeaveService {

    private static final int DEFAULT_BATCH_SIZE = 100;

    private final MembershipService membershipService;
    private final HashRingService hashRingService;
    private final StorageService storageService;
    private final ReplicaDataClient replicaDataClient;
    private final NodeProperties nodeProperties;

    public GracefulLeaveService(
            MembershipService membershipService,
            HashRingService hashRingService,
            StorageService storageService,
            ReplicaDataClient replicaDataClient,
            NodeProperties nodeProperties
    ) {
        this.membershipService = membershipService;
        this.hashRingService = hashRingService;
        this.storageService = storageService;
        this.replicaDataClient = replicaDataClient;
        this.nodeProperties = nodeProperties;
    }

    public synchronized void leaveCluster() {
        String nodeId = nodeProperties.getNodeId();
        if (nodeId == null || nodeId.isBlank()) {
            return;
        }

        MemberRecord self = membershipService.getMember(nodeId).orElse(null);
        if (self == null || self.status() == MemberStatus.DEAD) {
            return;
        }

        List<MemberRecord> beforeLeaveMembership = membershipService.getMembershipSnapshot().stream().toList();
        hashRingService.rebuildRing(beforeLeaveMembership);
        List<TokenRange> ownedRanges = hashRingService.getOwnedTokenRanges(nodeId);

        try {
            membershipService.markLeaving(nodeId);
            List<MemberRecord> leavingMembership = membershipService.getMembershipSnapshot().stream().toList();
            hashRingService.rebuildRing(leavingMembership);

            Map<String, MemberRecord> membersByNodeId = leavingMembership.stream()
                    .collect(Collectors.toMap(MemberRecord::nodeId, member -> member, (left, right) -> left));

            RebalanceMetricsRegistry.RebalanceToken token = null;
            if (!ownedRanges.isEmpty()) {
                token = RebalanceMetricsRegistry.begin("LEAVE", nodeId, nodeId, ownedRanges.size());
            }

            try {
                for (TokenRange range : ownedRanges) {
                    String successorAddress = resolveSuccessorAddress(range, nodeId, membersByNodeId)
                            .orElseThrow(() -> new IllegalStateException("No successor available for token range " + range));
                    transferRangeToSuccessor(range, successorAddress, nodeId);
                }
                RebalanceMetricsRegistry.complete(token);
            } catch (RuntimeException exception) {
                RebalanceMetricsRegistry.fail(token, exception);
                throw exception;
            }

            membershipService.markDead(nodeId);
            hashRingService.rebuildRing(membershipService.getMembershipSnapshot());
        } catch (RuntimeException ex) {
            // rollback to ALIVE if graceful leave cannot complete
            membershipService.updateHeartbeat(nodeId, self.address(), self.incarnation() + 1);
            hashRingService.rebuildRing(membershipService.getMembershipSnapshot());
            throw ex;
        }
    }

    private Optional<String> resolveSuccessorAddress(TokenRange range, String leavingNodeId, Map<String, MemberRecord> membersByNodeId) {
        return hashRingService.findOwnerForToken(range.endInclusive())
                .filter(ownerNodeId -> !ownerNodeId.equals(leavingNodeId))
                .map(membersByNodeId::get)
                .filter(member -> member != null && member.status() == MemberStatus.ALIVE)
                .map(MemberRecord::address)
                .filter(address -> address != null && !address.isBlank());
    }

    private void transferRangeToSuccessor(TokenRange range, String successorAddress, String sourceNodeId) {
        String cursor = null;
        boolean done = false;
        while (!done) {
            ReplicaStreamPage page = storageService.scanRangePage(
                    range.startExclusive(),
                    range.endInclusive(),
                    DEFAULT_BATCH_SIZE,
                    cursor
            );

            for (StoredValue entry : page.entries()) {
                ReplicaRecord record = new ReplicaRecord(
                        entry.key(),
                        entry.value(),
                        entry.timestamp(),
                        entry.tombstone(),
                        entry.token(),
                        sourceNodeId
                );
                boolean ack = replicaDataClient.putReplica(successorAddress, leaveRequestId(sourceNodeId), record);
                if (!ack) {
                    throw new IllegalStateException(
                            "Graceful leave transfer failed for key '" + entry.key() + "' to successor " + successorAddress
                    );
                }
            }

            cursor = page.nextCursor();
            done = page.done();
        }
    }

    private String leaveRequestId(String nodeId) {
        return "leave-" + (nodeId == null || nodeId.isBlank() ? "unknown" : nodeId);
    }
}
