package com.flowcortex.user.accountdeletion;

import java.time.Instant;
import java.util.UUID;

/** One row of {@code account_deletion_request}: a member's request to delete the account, and when it ends. */
public record AccountDeletionRequest(
        UUID id,
        UUID userId,
        State state,
        Instant requestedAt,
        Instant scheduledAt,
        Instant cancelledAt) {

    /** The stored states; a request is {@code PENDING} until the member cancels it. */
    public enum State {
        PENDING("pending"),
        CANCELLED("cancelled");

        private final String value;

        State(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        static State of(String value) {
            for (State state : values()) {
                if (state.value.equals(value)) {
                    return state;
                }
            }
            throw new IllegalStateException("Unknown account deletion state: " + value);
        }
    }
}
