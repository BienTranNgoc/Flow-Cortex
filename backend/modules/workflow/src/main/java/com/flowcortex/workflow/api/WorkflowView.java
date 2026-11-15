package com.flowcortex.workflow.api;

import com.flowcortex.credit.api.CreditAccounts;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Public view of a {@code workflow} row returned by {@link WorkflowService}.
 * {@code steps} is always populated in definition order. {@code available} is false while a step
 * type it uses is switched off; starting a run of it is then refused. {@code status} is {@code draft} or
 * {@code published}. {@code publishedVersionId} is the version the pointer names; {@code servedVersion} is the
 * number of the version the caller is served in place of the live draft, null when {@code steps} are the live ones.
 */
public record WorkflowView(
        UUID id, UUID ownerId, String name, boolean shared, Instant createdAt, Instant updatedAt,
        List<WorkflowStepView> steps, boolean available, String status, UUID publishedVersionId,
        Integer servedVersion) {

    /** Sum of the steps' configured costs; {@code null} while credits are switched off. */
    public Long estimatedCredits(CreditAccounts credits) {
        return credits.enabled() ? credits.estimate(steps.stream().map(WorkflowStepView::type).toList()) : null;
    }
}
