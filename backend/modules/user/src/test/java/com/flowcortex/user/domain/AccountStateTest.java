package com.flowcortex.user.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AccountStateTest {

    private static final Instant NOW = Instant.parse("2026-11-08T03:04:05Z");
    private static final Instant CONFIRMED = NOW.minus(Duration.ofDays(3));

    @Test
    void suspendedWinsOverUnconfirmedAndLockedOut() {
        assertThat(AccountState.of(UserStatus.SUSPENDED, null, NOW.plus(Duration.ofHours(1)), NOW))
                .isEqualTo(AccountState.SUSPENDED);
    }

    @Test
    void anActiveUnconfirmedAccountIsPendingConfirmation() {
        assertThat(AccountState.of(UserStatus.ACTIVE, null, null, NOW)).isEqualTo(AccountState.PENDING_CONFIRMATION);
    }

    @Test
    void aRunningLockoutIsLocked() {
        assertThat(AccountState.of(UserStatus.ACTIVE, CONFIRMED, NOW.plus(Duration.ofMinutes(10)), NOW))
                .isEqualTo(AccountState.LOCKED);
    }

    @Test
    void aLockoutEndingExactlyNowIsActive() {
        assertThat(AccountState.of(UserStatus.ACTIVE, CONFIRMED, NOW, NOW)).isEqualTo(AccountState.ACTIVE);
    }

    @Test
    void aLockoutThatEndedAMinuteAgoIsActive() {
        assertThat(AccountState.of(UserStatus.ACTIVE, CONFIRMED, NOW.minus(Duration.ofMinutes(1)), NOW))
                .isEqualTo(AccountState.ACTIVE);
    }

    @Test
    void noLockoutIsActive() {
        assertThat(AccountState.of(UserStatus.ACTIVE, CONFIRMED, null, NOW)).isEqualTo(AccountState.ACTIVE);
    }

    @Test
    void wireValuesRoundTripAndAnUnknownOneIsEmpty() {
        assertThat(AccountState.ACTIVE.wire()).isEqualTo("active");
        assertThat(AccountState.PENDING_CONFIRMATION.wire()).isEqualTo("pending_confirmation");
        assertThat(AccountState.LOCKED.wire()).isEqualTo("locked");
        assertThat(AccountState.SUSPENDED.wire()).isEqualTo("suspended");
        for (AccountState state : AccountState.values()) {
            assertThat(AccountState.fromWire(state.wire())).contains(state);
        }
        assertThat(AccountState.fromWire("deleted")).isEmpty();
        assertThat(AccountState.fromWire(null)).isEmpty();
        assertThat(AccountState.fromWire("ACTIVE")).isEmpty();
    }
}
