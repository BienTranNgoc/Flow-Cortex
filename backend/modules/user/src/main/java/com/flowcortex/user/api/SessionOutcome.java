package com.flowcortex.user.api;

import java.time.Duration;

/**
 * What a first factor (password, Google, emailed link) leads to: a session, or, for an account with TOTP on,
 * a challenge that a valid code turns into a session.
 */
public sealed interface SessionOutcome {

    /** The sign-in is complete. */
    record Opened(AuthSession session) implements SessionOutcome {
    }

    /**
     * A code is still owed. {@code rawChallenge} is the one-time value the web layer hands to the browser in a
     * cookie (only its hash is stored); it lives for {@code ttl}.
     */
    record Challenged(String rawChallenge, Duration ttl) implements SessionOutcome {

        /** Keeps the challenge out of logs and exception messages. */
        @Override
        public String toString() {
            return "Challenged[rawChallenge=***, ttl=" + ttl + "]";
        }
    }
}
