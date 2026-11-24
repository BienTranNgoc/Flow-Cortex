package com.flowcortex.user.apicall;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** What a keyed write does given the row already stored for its key. */
final class IdempotencyDecision {

    enum State { RUNNING, DONE }

    enum Outcome { PROCEED, REPLAY, KEY_REUSED, IN_PROGRESS, TAKE_OVER }

    record Row(String fingerprint, State state, Instant reservedAt, Instant expiresAt) {}

    private IdempotencyDecision() {}

    /**
     * An expired or missing row proceeds; a different fingerprint is a reused key before any state is
     * looked at; a finished row replays; a running one is in progress until it is older than {@code staleAfter}.
     */
    static Outcome decide(Optional<Row> existing, String fingerprint, Instant now, Duration staleAfter) {
        if (existing.isEmpty() || !existing.get().expiresAt().isAfter(now)) {
            return Outcome.PROCEED;
        }
        Row row = existing.get();
        if (!row.fingerprint().equals(fingerprint)) {
            return Outcome.KEY_REUSED;
        }
        if (row.state() == State.DONE) {
            return Outcome.REPLAY;
        }
        return row.reservedAt().plus(staleAfter).isBefore(now) ? Outcome.TAKE_OVER : Outcome.IN_PROGRESS;
    }
}
