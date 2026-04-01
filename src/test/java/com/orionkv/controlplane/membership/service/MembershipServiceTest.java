package com.orionkv.controlplane.membership.service;

import com.orionkv.controlplane.membership.model.MemberRecord;
import com.orionkv.controlplane.membership.model.MemberStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class MembershipServiceTest {

    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-03-29T20:00:00Z"), ZoneOffset.UTC);
    private final MembershipService membershipService = new MembershipService(fixedClock);

    @Test
    void shouldReplaceLocalRecordWhenRemoteIncarnationIsHigher() {
        membershipService.mergeRemoteMembership(new MemberRecord(
                "node-a",
                "10.0.0.1:8080",
                MemberStatus.ALIVE,
                1,
                Instant.parse("2026-03-29T19:00:00Z")
        ));

        MemberRecord merged = membershipService.mergeRemoteMembership(new MemberRecord(
                "node-a",
                "10.0.0.2:8080",
                MemberStatus.SUSPECT,
                2,
                Instant.parse("2026-03-29T19:30:00Z")
        ));

        assertThat(merged.address()).isEqualTo("10.0.0.2:8080");
        assertThat(merged.incarnation()).isEqualTo(2);
        assertThat(merged.status()).isEqualTo(MemberStatus.SUSPECT);
    }

    @Test
    void shouldPreferDeadStatusWhenIncarnationMatches() {
        membershipService.mergeRemoteMembership(new MemberRecord(
                "node-a",
                "10.0.0.1:8080",
                MemberStatus.ALIVE,
                3,
                Instant.parse("2026-03-29T19:00:00Z")
        ));

        MemberRecord merged = membershipService.mergeRemoteMembership(new MemberRecord(
                "node-a",
                "10.0.0.1:8080",
                MemberStatus.DEAD,
                3,
                Instant.parse("2026-03-29T19:05:00Z")
        ));

        assertThat(merged.status()).isEqualTo(MemberStatus.DEAD);
    }

    @Test
    void shouldMarkHeartbeatAliveAndRefreshLastSeen() {
        MemberRecord updated = membershipService.updateHeartbeat("node-b", "10.0.0.3:8080", 5);

        assertThat(updated.status()).isEqualTo(MemberStatus.ALIVE);
        assertThat(updated.incarnation()).isEqualTo(5);
        assertThat(updated.lastSeen()).isEqualTo(Instant.now(fixedClock));
    }

    @Test
    void shouldMarkMembersSuspectAndDead() {
        membershipService.updateHeartbeat("node-c", "10.0.0.4:8080", 1);

        MemberRecord suspect = membershipService.markSuspect("node-c");
        MemberRecord dead = membershipService.markDead("node-c");

        assertThat(suspect.status()).isEqualTo(MemberStatus.SUSPECT);
        assertThat(dead.status()).isEqualTo(MemberStatus.DEAD);
    }
}
