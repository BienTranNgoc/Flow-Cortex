package com.flowcortex.billing.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ApplyRetryTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");

    @ParameterizedTest
    @CsvSource({
        "0, 30",
        "1, 60",
        "2, 120",
        "3, 240",
        "4, 480",
        "5, 960",
        "6, 1920",
        "7, 3600",
        "8, 3600"
    })
    void theWaitDoublesFromThirtySecondsAndStopsAtOneHour(int attemptsSoFar, long seconds) {
        assertThat(ApplyRetry.afterFailure(attemptsSoFar, NOW))
                .isEqualTo(new ApplyRetry.RetryAt(NOW.plus(Duration.ofSeconds(seconds))));
    }

    @Test
    void theTenthFailedAttemptExhaustsTheCallback() {
        assertThat(ApplyRetry.afterFailure(9, NOW)).isEqualTo(new ApplyRetry.Exhausted());
    }

    @Test
    void moreAttemptsThanTheLimitStayExhausted() {
        assertThat(ApplyRetry.afterFailure(10, NOW)).isEqualTo(new ApplyRetry.Exhausted());
        assertThat(ApplyRetry.afterFailure(Integer.MAX_VALUE, NOW)).isEqualTo(new ApplyRetry.Exhausted());
    }

    @Test
    void aNegativeAttemptCountIsABug() {
        assertThatThrownBy(() -> ApplyRetry.afterFailure(-1, NOW)).isInstanceOf(IllegalArgumentException.class);
    }
}
