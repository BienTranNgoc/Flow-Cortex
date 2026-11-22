package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.media.api.MediaLimits;
import com.flowcortex.workflow.api.AudienceView.StartKind;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowStep;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import com.flowcortex.workflow.infrastructure.WorkflowStepRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** What the start form of a workflow asks for, as onboarding reads it. */
class WorkflowAudienceImplTest {

    private final WorkflowRepository workflows = mock(WorkflowRepository.class);
    private final WorkflowStepRepository steps = mock(WorkflowStepRepository.class);
    private final UUID workflowId = UUID.randomUUID();

    private WorkflowAudienceImpl audience() {
        MediaJobs mediaJobs = mock(MediaJobs.class);
        when(mediaJobs.providerLimits(any()))
                .thenReturn(new MediaLimits(Duration.ofMinutes(5), Duration.ofMinutes(2), 1000L));
        return new WorkflowAudienceImpl(workflows, steps, new StepExecutorRegistry(mediaJobs, Clock.systemUTC()));
    }

    private StartKind startKindOf(String type, String config) {
        when(workflows.findById(workflowId))
                .thenReturn(Optional.of(new Workflow(workflowId, UUID.randomUUID(), "wf", true)));
        when(steps.findByWorkflowIdOrderByStepOrderAsc(workflowId)).thenReturn(List.of(
                new WorkflowStep(UUID.randomUUID(), workflowId, (short) 1, "a", type, null, config)));
        return audience().describe(workflowId).startKind();
    }

    @Test
    void aFirstStepThatTakesTheMembersLinkAsksForALink() {
        assertThat(startKindOf("fetch_video", null)).isEqualTo(StartKind.LINK);
    }

    @Test
    void aFetchStepWhoseLinkIsInItsConfigAsksForText() {
        assertThat(startKindOf("fetch_video", "{\"url\":\"https://cdn.example/v.mp4\"}")).isEqualTo(StartKind.TEXT);
    }

    @Test
    void aFirstStepThatReadsTheUploadAsksForAFile() {
        assertThat(startKindOf("ingest_video", null)).isEqualTo(StartKind.UPLOAD);
        assertThat(startKindOf("ingest_image", null)).isEqualTo(StartKind.UPLOAD);
        assertThat(startKindOf("probe", null)).isEqualTo(StartKind.UPLOAD);
    }

    @Test
    void anyOtherFirstStepAsksForText() {
        assertThat(startKindOf("echo", null)).isEqualTo(StartKind.TEXT);
    }
}
