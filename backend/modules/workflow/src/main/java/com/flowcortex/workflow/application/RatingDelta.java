package com.flowcortex.workflow.application;

import java.util.Optional;

/** What one review write adds to a workflow's rating totals: a change of the review count and of the star sum. */
public record RatingDelta(int count, int sum) {

    /**
     * The change from a review's stars before the write ({@code null} when it did not exist) to its stars after
     * ({@code null} when it no longer exists); empty when the totals stay as they are.
     */
    static Optional<RatingDelta> of(Integer before, Integer after) {
        if (before == null && after == null) {
            return Optional.empty();
        }
        if (before == null) {
            return Optional.of(new RatingDelta(1, after));
        }
        if (after == null) {
            return Optional.of(new RatingDelta(-1, -before));
        }
        return before.equals(after) ? Optional.empty() : Optional.of(new RatingDelta(0, after - before));
    }
}
