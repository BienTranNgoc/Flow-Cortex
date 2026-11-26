package com.flowcortex.trash.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TrashClockTest {

    private static final Instant NOW = Instant.parse("2026-11-23T10:00:00Z");
    private static final Duration RETENTION = Duration.ofDays(30);
    private static final Duration LEAD = Duration.ofDays(3);

    @Test
    void expiresAtIsDeleteTimePlusRetention() {
        Instant deletedAt = NOW.minus(Duration.ofDays(5));

        assertThat(TrashClock.expiresAt(deletedAt, RETENTION)).isEqualTo(deletedAt.plus(RETENTION));
    }

    @Test
    void remainingDaysRoundsUp() {
        Instant deletedAt = NOW.minus(Duration.ofDays(29)).minus(Duration.ofHours(2));

        assertThat(TrashClock.remainingDays(TrashClock.expiresAt(deletedAt, RETENTION), NOW)).isEqualTo(1);
        assertThat(TrashClock.remainingDays(NOW.plus(Duration.ofDays(2)).plusSeconds(1), NOW)).isEqualTo(3);
        assertThat(TrashClock.remainingDays(NOW.plus(Duration.ofDays(2)), NOW)).isEqualTo(2);
    }

    @Test
    void remainingDaysNeverBelowZero() {
        Instant deletedAt = NOW.minus(Duration.ofDays(30)).minus(Duration.ofHours(1));

        assertThat(TrashClock.remainingDays(TrashClock.expiresAt(deletedAt, RETENTION), NOW)).isZero();
    }

    @Test
    void exactlyAtExpiryIsZero() {
        assertThat(TrashClock.remainingDays(NOW, NOW)).isZero();
    }

    @Test
    void sweepCutoffIsNowMinusRetention() {
        assertThat(TrashClock.sweepCutoff(NOW, RETENTION)).isEqualTo(NOW.minus(RETENTION));
    }

    @Test
    void reminderClaimBound() {
        Instant upTo = TrashClock.reminderClaimUpTo(NOW, RETENTION, LEAD);

        assertThat(NOW.minus(Duration.ofDays(28))).isBeforeOrEqualTo(upTo);
        assertThat(NOW.minus(Duration.ofDays(20))).isAfter(upTo);
        assertThat(NOW.minus(Duration.ofDays(40))).isBeforeOrEqualTo(upTo);
        assertThat(upTo).isEqualTo(NOW.minus(Duration.ofDays(27)));
    }

    @Test
    void reminderDeletionDate() {
        Instant expiresInTwoDays = NOW.plus(Duration.ofDays(2));
        assertThat(TrashClock.deletionDate(expiresInTwoDays, NOW)).isEqualTo(expiresInTwoDays);

        Instant expiredTenDaysAgo = NOW.minus(Duration.ofDays(10));
        assertThat(TrashClock.deletionDate(expiredTenDaysAgo, NOW)).isEqualTo(NOW.plus(Duration.ofHours(24)));
    }

    @Test
    void remindedLongEnoughForPurge() {
        assertThat(TrashClock.reminderGrace()).isEqualTo(Duration.ofHours(24));
        assertThat(TrashClock.remindedLongEnoughForPurge(NOW.minus(Duration.ofHours(24)).plusSeconds(60), NOW))
                .isFalse();
        assertThat(TrashClock.remindedLongEnoughForPurge(NOW.minus(Duration.ofHours(24)), NOW)).isTrue();
        assertThat(TrashClock.remindedLongEnoughForPurge(NOW.minus(Duration.ofDays(5)), NOW)).isTrue();
        assertThat(TrashClock.remindedLongEnoughForPurge(null, NOW)).isFalse();
    }
}
