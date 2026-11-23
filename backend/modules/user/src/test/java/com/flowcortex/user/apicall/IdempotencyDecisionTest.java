package com.flowcortex.user.apicall;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.apicall.IdempotencyDecision.Outcome;
import com.flowcortex.user.apicall.IdempotencyDecision.Row;
import com.flowcortex.user.apicall.IdempotencyDecision.State;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class IdempotencyDecisionTest {

    private static final Instant NOW = Instant.parse("2026-11-15T12:00:00Z");
    private static final Duration STALE_AFTER = Duration.ofMinutes(5);
    private static final String PRINT = "a".repeat(64);
    private static final String OTHER_PRINT = "b".repeat(64);

    private static Row row(State state, String fingerprint, Duration reservedAgo, Duration expiresIn) {
        return new Row(fingerprint, state, NOW.minus(reservedAgo), NOW.plus(expiresIn));
    }

    private static Outcome decide(Optional<Row> existing, String fingerprint) {
        return IdempotencyDecision.decide(existing, fingerprint, NOW, STALE_AFTER);
    }

    @Test
    void noRowProceeds() {
        assertThat(decide(Optional.empty(), PRINT)).isEqualTo(Outcome.PROCEED);
    }

    @Test
    void anExpiredRowProceedsWhateverItHolds() {
        Row expired = row(State.DONE, OTHER_PRINT, Duration.ofHours(25), Duration.ofHours(-1));

        assertThat(decide(Optional.of(expired), PRINT)).isEqualTo(Outcome.PROCEED);
    }

    @Test
    void aDifferentFingerprintIsAReusedKeyWhetherRunningOrDone() {
        Row running = row(State.RUNNING, OTHER_PRINT, Duration.ofMinutes(1), Duration.ofHours(23));
        Row done = row(State.DONE, OTHER_PRINT, Duration.ofMinutes(10), Duration.ofHours(23));

        assertThat(decide(Optional.of(running), PRINT)).isEqualTo(Outcome.KEY_REUSED);
        assertThat(decide(Optional.of(done), PRINT)).isEqualTo(Outcome.KEY_REUSED);
    }

    @Test
    void aDoneRowWithTheSameFingerprintIsReplayed() {
        Row done = row(State.DONE, PRINT, Duration.ofMinutes(10), Duration.ofHours(23));

        assertThat(decide(Optional.of(done), PRINT)).isEqualTo(Outcome.REPLAY);
    }

    @Test
    void aFreshReservationIsStillInProgress() {
        Row running = row(State.RUNNING, PRINT, Duration.ofMinutes(4).plusSeconds(59), Duration.ofHours(23));

        assertThat(decide(Optional.of(running), PRINT)).isEqualTo(Outcome.IN_PROGRESS);
    }

    @Test
    void aReservationOlderThanTheStaleLimitIsTakenOver() {
        Row running = row(State.RUNNING, PRINT, Duration.ofMinutes(5).plusSeconds(1), Duration.ofHours(23));

        assertThat(decide(Optional.of(running), PRINT)).isEqualTo(Outcome.TAKE_OVER);
    }
}
