package com.flowcortex.user.stepup;

import com.flowcortex.user.api.StepUpPurpose;
import java.time.Instant;
import java.util.UUID;

/** Whether a spent proof may authorise the action: pure, so every rule has its own case. */
enum StepUpVerdict {

    ACCEPTED,
    REFUSED;

    /** Accepted only for the row's own user and purpose, strictly before its expiry; {@code null} row = nothing was spent. */
    static StepUpVerdict of(ConsumedProof row, UUID actingUser, StepUpPurpose required, Instant now) {
        if (row == null
                || !row.userId().equals(actingUser)
                || !required.name().equals(row.purpose())
                || !now.isBefore(row.expiresAt())) {
            return REFUSED;
        }
        return ACCEPTED;
    }
}
