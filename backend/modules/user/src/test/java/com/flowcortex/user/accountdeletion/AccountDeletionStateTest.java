package com.flowcortex.user.accountdeletion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class AccountDeletionStateTest {

    private static final Instant REQUESTED = Instant.parse("2026-11-01T10:00:00Z");
    private static final Instant DEADLINE = Instant.parse("2026-12-01T10:00:00Z");

    @Test
    void noRequestIsNone() {
        assertThat(AccountDeletionState.of(null, null, DEADLINE)).isEqualTo(AccountDeletionState.NONE);
    }

    @Test
    void oneSecondBeforeTheDeadlineIsPending() {
        assertThat(AccountDeletionState.of(REQUESTED, DEADLINE, DEADLINE.minusSeconds(1)))
                .isEqualTo(AccountDeletionState.PENDING);
    }

    @Test
    void atTheDeadlineIsExpired() {
        assertThat(AccountDeletionState.of(REQUESTED, DEADLINE, DEADLINE)).isEqualTo(AccountDeletionState.EXPIRED);
    }

    @Test
    void aMissingDeadlineIsExpired() {
        assertThat(AccountDeletionState.of(REQUESTED, null, REQUESTED)).isEqualTo(AccountDeletionState.EXPIRED);
    }
}
