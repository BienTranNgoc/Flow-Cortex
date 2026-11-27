package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.media.api.MediaAssetInput;
import com.flowcortex.media.api.MediaJobInput;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.media.api.MediaLimits;
import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.infrastructure.RunStepOutputVersionRepository;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/** Which steps of a retried run hold the run's upload: the rule run start applies, not "is the step remote". */
class RunResumptionUploadRuleTest {

    private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");

    private final WorkflowRepository workflows = mock(WorkflowRepository.class);
    private final WorkflowRunRepository runs = mock(WorkflowRunRepository.class);
    private final RunStepRepository runSteps = mock(RunStepRepository.class);
    private final MediaJobs mediaJobs = mock(MediaJobs.class);
    private final UUID owner = UUID.randomUUID();
    private final UUID workflowId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private RunResumption resumption;

    /** A failed run of {@code types} whose steps before the last completed and whose last failed. */
    private List<RunStep> givenFailedRun(String... types) {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(mediaJobs.providerLimits(anyString()))
                .thenReturn(new MediaLimits(Duration.ofMinutes(5), Duration.ofMinutes(2), 1000L));
        when(mediaJobs.isEnabled()).thenReturn(true);
        when(mediaJobs.runInputsStored(any())).thenReturn(true);
        MediaJobInput upload = new MediaJobInput("media", "flowcortex-media", "runs/" + runId + "/inputs/media");
        when(mediaJobs.boundRunInputs(runId)).thenReturn(List.of(new MediaAssetInput(UUID.randomUUID(), upload)));
        when(mediaJobs.copyRunInputs(any(), any())).thenAnswer(call -> call.getArgument(1));

        WorkflowRun run = new WorkflowRun(runId, workflowId, owner, "clip.mp4", NOW);
        List<RunStep> steps = new ArrayList<>();
        for (int index = 0; index < types.length; index++) {
            RunStep step = new RunStep(UUID.randomUUID(), runId, (short) (index + 1), types[index], null, null, null);
            step.markRunning(NOW);
            if (index < types.length - 1) {
                step.markCompleted("resolved", "out", NOW);
            } else {
                step.markError("resolved", "store_unavailable", "boom", NOW);
            }
            steps.add(step);
        }
        run.markError("boom", NOW);
        when(runs.findByIdAndOwnerId(runId, owner)).thenReturn(Optional.of(run));
        when(runSteps.findByRunIdOrderByStepOrderAsc(runId)).thenReturn(steps);
        when(runSteps.reArm(any(), any(), anyShort(), any())).thenReturn(1);
        when(runs.reopen(any(), any())).thenReturn(1);
        when(runs.save(any())).thenAnswer(call -> call.getArgument(0));
        when(runSteps.saveAll(any())).thenAnswer(call -> call.getArgument(0));
        when(workflows.findVisibleTo(workflowId, owner))
                .thenReturn(Optional.of(new Workflow(workflowId, owner, "wf", false)));

        resumption = new RunResumption(workflows, runs, runSteps, mock(RunStepOutputVersionRepository.class),
                new StepExecutorRegistry(mediaJobs, Clock.systemUTC()), mediaJobs,
                new TransactionTemplate(transactionManager), mock(RunUpdates.class), mock(RunStartGuards.class),
                mock(ApplicationEventPublisher.class), RunViewFactsFactories.insightOff(), true);
        return steps;
    }

    private String reArmedInput(RunStep step) {
        ArgumentCaptor<String> input = ArgumentCaptor.forClass(String.class);
        verify(runSteps).reArm(eq(step.getId()), eq(runId), anyShort(), input.capture());
        return input.getValue();
    }

    @Test
    void retryOfAnIngestStepRestoresTheUploadItReads() {
        List<RunStep> steps = givenFailedRun("ingest_video");

        resumption.retryStep(owner, runId, steps.getFirst().getId());

        assertThat(reArmedInput(steps.getFirst())).contains("\"key\":\"media\"");
    }

    @Test
    void retryOfTheStepAfterAnIngestStepRestoresNoUpload() {
        List<RunStep> steps = givenFailedRun("ingest_video", "probe");

        resumption.retryStep(owner, runId, steps.get(1).getId());

        assertThat(reArmedInput(steps.get(1))).isNull();
    }

    @Test
    void aRunRetryGivesTheUploadToTheIngestStepOnly() {
        givenFailedRun("ingest_video", "probe");

        resumption.retryRun(owner, runId, 1, false);

        ArgumentCaptor<List<RunStep>> saved = ArgumentCaptor.forClass(List.class);
        verify(runSteps).saveAll(saved.capture());
        assertThat(saved.getValue()).extracting(RunStep::getType).containsExactly("ingest_video", "probe");
        assertThat(saved.getValue().get(0).getInput()).contains("\"key\":\"media\"");
        assertThat(saved.getValue().get(1).getInput()).isNull();
    }

    @Test
    void aRunRetryOfAFetchRunStoresNoUploadOnAnyStep() {
        givenFailedRun("fetch_video", "probe");
        when(mediaJobs.boundRunInputs(runId)).thenReturn(List.of());

        resumption.retryRun(owner, runId, 1, false);

        ArgumentCaptor<List<RunStep>> saved = ArgumentCaptor.forClass(List.class);
        verify(runSteps).saveAll(saved.capture());
        assertThat(saved.getValue()).extracting(RunStep::getInput).containsOnlyNulls();
    }

    @Test
    void anExpiredRunInputRefusesTheRetryOfAnIngestStepBeforeItIsCharged() {
        List<RunStep> steps = givenFailedRun("ingest_video");
        when(mediaJobs.runInputsStored(runId)).thenReturn(false);

        assertThatThrownBy(() -> resumption.retryStep(owner, runId, steps.getFirst().getId()))
                .isInstanceOf(ConflictException.class);
    }
}
