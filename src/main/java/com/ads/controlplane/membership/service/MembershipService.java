package com.ads.controlplane.membership.service;

import com.ads.controlplane.membership.model.MemberRecord;
import com.ads.controlplane.membership.model.MemberStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class MembershipService {

    private final ConcurrentHashMap<String, MemberRecord> members = new ConcurrentHashMap<>();
    private final Clock clock;

    public MembershipService() {
        this(Clock.systemUTC());
    }

    public MembershipService(Clock clock) {
        this.clock = clock;
    }

    public Collection<MemberRecord> getMembershipSnapshot() {
        return List.copyOf(members.values());
    }

    public Optional<MemberRecord> getMember(String nodeId) {
        return Optional.ofNullable(members.get(nodeId));
    }

    public Map<String, MemberRecord> getMembershipView() {
        return Map.copyOf(members);
    }

    public MemberRecord mergeRemoteMembership(MemberRecord remoteRecord) {
        validate(remoteRecord);
        return members.compute(remoteRecord.nodeId(), (nodeId, localRecord) ->
                shouldReplace(localRecord, remoteRecord) ? remoteRecord : localRecord
        );
    }

    public List<MemberRecord> mergeRemoteMembership(Collection<MemberRecord> remoteMembership) {
        if (remoteMembership == null || remoteMembership.isEmpty()) {
            return List.of();
        }

        return remoteMembership.stream()
                .map(this::mergeRemoteMembership)
                .collect(Collectors.toList());
    }

    public MemberRecord updateHeartbeat(String nodeId, String address, long incarnation) {
        Instant now = Instant.now(clock);
        return members.compute(nodeId, (id, existing) -> {
            long nextIncarnation = existing == null ? incarnation : Math.max(existing.incarnation(), incarnation);
            String nextAddress = address != null ? address : existing == null ? null : existing.address();
            return new MemberRecord(id, nextAddress, MemberStatus.ALIVE, nextIncarnation, now);
        });
    }

    public MemberRecord markSuspect(String nodeId) {
        return members.computeIfPresent(nodeId, (id, existing) ->
                new MemberRecord(id, existing.address(), MemberStatus.SUSPECT, existing.incarnation(), existing.lastSeen())
        );
    }

    public MemberRecord markDead(String nodeId) {
        return members.computeIfPresent(nodeId, (id, existing) ->
                new MemberRecord(id, existing.address(), MemberStatus.DEAD, existing.incarnation(), existing.lastSeen())
        );
    }

    private boolean shouldReplace(MemberRecord localRecord, MemberRecord remoteRecord) {
        if (localRecord == null) {
            return true;
        }
        if (remoteRecord.incarnation() != localRecord.incarnation()) {
            return remoteRecord.incarnation() > localRecord.incarnation();
        }

        int remotePriority = statusPriority(remoteRecord.status());
        int localPriority = statusPriority(localRecord.status());
        if (remotePriority != localPriority) {
            return remotePriority > localPriority;
        }

        return remoteRecord.lastSeen().isAfter(localRecord.lastSeen());
    }

    private int statusPriority(MemberStatus status) {
        return switch (status) {
            case ALIVE -> 1;
            case SUSPECT -> 2;
            case DEAD -> 3;
        };
    }

    private void validate(MemberRecord record) {
        if (record.nodeId() == null || record.nodeId().isBlank()) {
            throw new IllegalArgumentException("nodeId must not be blank");
        }
        if (record.status() == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        if (record.lastSeen() == null) {
            throw new IllegalArgumentException("lastSeen must not be null");
        }
    }
}
