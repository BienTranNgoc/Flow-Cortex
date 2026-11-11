package com.flowcortex.onboarding.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.workflow.api.AudienceView;
import com.flowcortex.workflow.api.AudienceView.Audience;
import com.flowcortex.workflow.api.AudienceView.StartKind;
import com.flowcortex.workflow.api.WorkflowStepView;
import com.flowcortex.workflow.api.WorkflowView;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SampleEligibilityTest {

    private static final WorkflowStepView ECHO = step("echo");

    private static WorkflowStepView step(String type) {
        return new WorkflowStepView(UUID.randomUUID(), 1, "step", type, "inline", null, null);
    }

    private static AudienceView view(Audience audience, List<WorkflowStepView> steps, boolean available,
            StartKind startKind) {
        WorkflowView workflow = new WorkflowView(UUID.randomUUID(), UUID.randomUUID(), "Sample", true,
                Instant.EPOCH, Instant.EPOCH, steps, available, "published");
        return new AudienceView(audience, workflow, startKind);
    }

    @Test
    void notFoundIsNotFound() {
        assertThat(SampleEligibility.judge(new AudienceView(Audience.NOT_FOUND, null, null)))
                .contains(IneligibleReason.NOT_FOUND);
    }

    @Test
    void deletedIsDeleted() {
        assertThat(SampleEligibility.judge(view(Audience.DELETED, List.of(ECHO), true, StartKind.TEXT)))
                .contains(IneligibleReason.DELETED);
    }

    @Test
    void draftIsUnpublished() {
        assertThat(SampleEligibility.judge(view(Audience.DRAFT, List.of(ECHO), true, StartKind.TEXT)))
                .contains(IneligibleReason.UNPUBLISHED);
    }

    @Test
    void ownerOnlyIsPrivate() {
        assertThat(SampleEligibility.judge(view(Audience.OWNER_ONLY, List.of(ECHO), true, StartKind.TEXT)))
                .contains(IneligibleReason.PRIVATE);
    }

    @Test
    void noStepsIsNoSteps() {
        assertThat(SampleEligibility.judge(view(Audience.ALL_MEMBERS, List.of(), true, StartKind.TEXT)))
                .contains(IneligibleReason.NO_STEPS);
    }

    @Test
    void switchedOffStepIsUnavailable() {
        assertThat(SampleEligibility.judge(view(Audience.ALL_MEMBERS, List.of(ECHO), false, StartKind.TEXT)))
                .contains(IneligibleReason.UNAVAILABLE);
    }

    @Test
    void uploadStartNeedsUpload() {
        assertThat(SampleEligibility.judge(view(Audience.ALL_MEMBERS, List.of(ECHO), true, StartKind.UPLOAD)))
                .contains(IneligibleReason.NEEDS_UPLOAD);
    }

    @Test
    void linkStartNeedsLink() {
        assertThat(SampleEligibility.judge(view(Audience.ALL_MEMBERS, List.of(ECHO), true, StartKind.LINK)))
                .contains(IneligibleReason.NEEDS_LINK);
    }

    @Test
    void approvalCheckpointIsManualStep() {
        assertThat(SampleEligibility.judge(
                view(Audience.ALL_MEMBERS, List.of(ECHO, step("APPROVAL_CHECKPOINT")), true, StartKind.TEXT)))
                .contains(IneligibleReason.MANUAL_STEP);
    }

    @Test
    void inlineSharedPublishedIsEligible() {
        assertThat(SampleEligibility.judge(view(Audience.ALL_MEMBERS, List.of(ECHO), true, StartKind.TEXT)))
                .isEqualTo(Optional.empty());
    }

    @Test
    void deletedWinsOverPrivate() {
        assertThat(SampleEligibility.judge(view(Audience.DELETED, List.of(), false, StartKind.UPLOAD)))
                .contains(IneligibleReason.DELETED);
    }
}
