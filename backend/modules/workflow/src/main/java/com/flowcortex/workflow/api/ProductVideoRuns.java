package com.flowcortex.workflow.api;

import java.util.List;
import java.util.UUID;

/** Starts the seeded Product Video workflow for a member from a confirmed draft. */
public interface ProductVideoRuns {

    /**
     * Starts a Product Video run. When {@code requestKey} is not {@code null} the start is idempotent per owner:
     * a key already used by {@code ownerId} returns that run unchanged and creates nothing, including when two
     * starts with the same key race.
     * @throws jakarta.validation.ConstraintViolationException if the draft or the upload selection is invalid
     *         (exactly one image, no duplicates); nothing is created
     * @throws org.springframework.web.server.ResponseStatusException 503 while Product Video, workflows or the
     *         media engine is switched off
     */
    RunView start(UUID ownerId, ProductVideoDraft draft, List<UUID> uploadIds, boolean acknowledgeCost,
            String requestKey);
}
