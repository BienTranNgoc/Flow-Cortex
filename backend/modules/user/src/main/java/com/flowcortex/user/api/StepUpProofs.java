package com.flowcortex.user.api;

import java.util.UUID;

/** Port for a sensitive action to demand the step-up proof its user minted by re-entering the password. */
public interface StepUpProofs {

    /**
     * Spends {@code proof} for {@code userId} and {@code purpose}. The proof is spent whatever the
     * outcome, even when it was minted for another user or purpose, and that spend commits even when
     * the caller's transaction rolls back.
     *
     * @throws StepUpRequiredException when the proof is missing, unknown, spent, expired, or minted
     *         for another user or purpose
     */
    void consume(UUID userId, StepUpPurpose purpose, String proof);
}
