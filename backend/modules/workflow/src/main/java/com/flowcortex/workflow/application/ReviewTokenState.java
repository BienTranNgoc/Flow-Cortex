package com.flowcortex.workflow.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * What an import token is worth to a caller. A row that is missing or another user's is {@link Expired}, so one
 * answer covers a token that never existed and one that is not the caller's; an imported row is a {@link Replay}
 * for its owner whatever the time.
 */
final class ReviewTokenState {

    /** The columns of a stored review that decide its state. {@code workflowId} is null until imported. */
    record ReviewRow(UUID userId, Instant expiresAt, UUID workflowId) {
    }

    sealed interface State permits Usable, Replay, Expired {
    }

    record Usable(ReviewRow row) implements State {
    }

    record Replay(UUID workflowId) implements State {
    }

    record Expired() implements State {
    }

    private ReviewTokenState() {
    }

    static State of(Optional<ReviewRow> row, UUID callerId, Instant now) {
        if (row.isEmpty() || !row.get().userId().equals(callerId)) {
            return new Expired();
        }
        ReviewRow review = row.get();
        if (review.workflowId() != null) {
            return new Replay(review.workflowId());
        }
        return review.expiresAt().isAfter(now) ? new Usable(review) : new Expired();
    }
}
