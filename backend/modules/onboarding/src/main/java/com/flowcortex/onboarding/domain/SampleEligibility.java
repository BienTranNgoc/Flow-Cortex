package com.flowcortex.onboarding.domain;

import com.flowcortex.workflow.api.AudienceView;
import com.flowcortex.workflow.api.AudienceView.StartKind;
import com.flowcortex.workflow.api.WorkflowView;
import java.util.Optional;

/** Whether a workflow can be offered to a new member as the sample; the first failing rule wins. */
public final class SampleEligibility {

    private static final String APPROVAL_CHECKPOINT = "APPROVAL_CHECKPOINT";

    private SampleEligibility() {
    }

    /** The first reason the workflow is not a valid sample, or empty when it is. */
    public static Optional<IneligibleReason> judge(AudienceView view) {
        switch (view.audience()) {
            case NOT_FOUND:
                return Optional.of(IneligibleReason.NOT_FOUND);
            case DELETED:
                return Optional.of(IneligibleReason.DELETED);
            case DRAFT:
                return Optional.of(IneligibleReason.UNPUBLISHED);
            case OWNER_ONLY:
                return Optional.of(IneligibleReason.PRIVATE);
            default:
                break;
        }
        WorkflowView workflow = view.workflow();
        if (workflow.steps().isEmpty()) {
            return Optional.of(IneligibleReason.NO_STEPS);
        }
        if (!workflow.available()) {
            return Optional.of(IneligibleReason.UNAVAILABLE);
        }
        if (view.startKind() == StartKind.UPLOAD) {
            return Optional.of(IneligibleReason.NEEDS_UPLOAD);
        }
        if (view.startKind() == StartKind.LINK) {
            return Optional.of(IneligibleReason.NEEDS_LINK);
        }
        boolean manual = workflow.steps().stream().anyMatch(step -> APPROVAL_CHECKPOINT.equals(step.type()));
        return manual ? Optional.of(IneligibleReason.MANUAL_STEP) : Optional.empty();
    }
}
