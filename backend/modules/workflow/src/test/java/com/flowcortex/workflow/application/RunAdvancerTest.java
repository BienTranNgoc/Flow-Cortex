package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.media.api.MediaCapability;
import com.flowcortex.media.api.MediaError;
import com.flowcortex.media.api.MediaJob;
import com.flowcortex.media.api.MediaJobCompleted;
import com.flowcortex.media.api.MediaJobInput;
import com.flowcortex.media.api.MediaJobProgressed;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.media.api.MediaLimits;
import com.flowcortex.media.api.MediaOutput;
import com.flowcortex.media.api.MediaProgress;
import com.flowcortex.media.api.MediaResult;
import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.domain.RunStepStatus;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.domain.WorkflowStep;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import com.flowcortex.workflow.infrastructure.WorkflowStepRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.TaskScheduler;

/**
 * {@link RunAdvancer} over mocked repositories and the real {@link RunStepTransactions}: which
 * transition each event causes. The row-count CAS queries themselves run against Postgres in
 * {@code RunStepTransactionsIT}; the engine end to end is {@code WorkflowRunEngineIT}.
 */
@ExtendWith(OutputCaptureExtension.class)
class RunAdvancerTest {

    private static final Instant NOW = Instant.parse("2026-09-30T10:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(120);
    private static final Duration PROBE_QUEUE = Duration.ofMinutes(30);
    private static final Duration PROBE_RUNTIME = Duration.ofMinutes(5);
    private static final String UPLOAD_REF =
            "{\"key\":\"source\",\"bucket\":\"flowcortex-uploads\",\"objectKey\":\"owner/upload\"}";
    private static final UUID SPEECH_REVISION = UUID.fromString("a71ea3ba-d72a-4c2c-985c-3a7fd98853c4");

    private final WorkflowRunRepository workflowRunRepository = mock(WorkflowRunRepository.class);
    private final RunStepRepository runStepRepository = mock(RunStepRepository.class);
    private final WorkflowStepRepository workflowStepRepository = mock(WorkflowStepRepository.class);
    private final MediaJobs mediaJobs = mock(MediaJobs.class);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        private final StepExecutorRegistry registry = new StepExecutorRegistry(List.of(
                new EchoStepExecutor(),
                new FailStepExecutor(),
                new UpperStepExecutor(),
                new RemoteStepExecutor(MediaCapability.PROBE.value(), mediaJobs, clock),
                new RemoteStepExecutor(MediaCapability.EXTRACT_AUDIO.value(), mediaJobs, clock),
                new RemoteStepExecutor(MediaCapability.RENDER_VIDEO.value(), mediaJobs, clock)));
    private final RunStepTransactions transactions = new RunStepTransactions(runStepRepository, workflowRunRepository);
    private final ReviewCheckpointTransactions checkpoints = mock(ReviewCheckpointTransactions.class);
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private RunAdvancer advancer(TaskExecutor taskExecutor) {
        return new RunAdvancer(workflowRunRepository, runStepRepository, workflowStepRepository, transactions,
                new InputResolver(), registry, taskExecutor, scheduler, Tracer.NOOP, ObservationRegistry.NOOP, meters,
                LEASE, clock, checkpoints, validator);
    }

    private final RunAdvancer advancer = advancer(Runnable::run);

    private final UUID runId = UUID.randomUUID();
    private final UUID workflowId = UUID.randomUUID();
    private final WorkflowRun run = new WorkflowRun(runId, workflowId, UUID.randomUUID(), "hello", NOW);

    private void givenRun(List<WorkflowStep> defs, List<RunStep> steps) {
        when(workflowRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(runStepRepository.findByRunIdOrderByStepOrderAsc(runId)).thenReturn(steps);
        when(workflowStepRepository.findByWorkflowIdOrderByStepOrderAsc(workflowId)).thenReturn(defs);
        when(scheduler.scheduleAtFixedRate(any(), any(Instant.class), any(Duration.class)))
                .thenAnswer(invocation -> mock(ScheduledFuture.class));
    }

    private WorkflowStep def(int order, String type) {
        return new WorkflowStep(UUID.randomUUID(), workflowId, (short) order, "step-" + order, type, null);
    }

    private RunStep pending(int order, String type) {
        return new RunStep(UUID.randomUUID(), runId, (short) order, type);
    }

    /** The same step as the repository returns it once completed. */
    private RunStep completed(int order, UUID id, String output) {
        RunStep step = new RunStep(id, runId, (short) order, "echo");
        step.markRunning(NOW);
        step.markCompleted("hello", output, NOW);
        return step;
    }

    /** An inline type whose output differs from its input, so input chaining is observable. */
    private static final class UpperStepExecutor implements StepExecutor {

        @Override
        public String type() {
            return "upper";
        }

        @Override
        public Mode mode() {
            return Mode.INLINE;
        }

        @Override
        public Duration maxRuntime() {
            return Duration.ofSeconds(60);
        }

        @Override
        public String execute(StepContext context) {
            return context.input().toUpperCase(java.util.Locale.ROOT);
        }
    }

    @Test
    void anExceptionWhileAdvancingToTheNextStepIsLoggedOnceAsAnErrorNotCountedAsALateResult(
            CapturedOutput output) {
        RunStep step1 = pending(1, "echo");
        RunStep step2 = pending(2, "echo");
        givenRun(List.of(def(1, "echo"), def(2, "echo")), List.of(step1, step2));
        when(runStepRepository.findByRunIdOrderByStepOrderAsc(runId))
                .thenReturn(List.of(step1, step2), List.of(completed(1, step1.getId(), "hello"), step2));
        when(runStepRepository.claimPending(eq(step1.getId()), any(), any(), any())).thenReturn(1);
        when(runStepRepository.claimPending(eq(step2.getId()), any(), any(), any()))
                .thenThrow(new IllegalStateException("database unavailable"));
        when(runStepRepository.recordPickup(any(), anyShort(), any(), any(), any(), anyShort())).thenReturn(1);
        when(runStepRepository.completeIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(1);
        when(runStepRepository.countByRunIdAndStatusNot(runId, RunStepStatus.COMPLETED)).thenReturn(1L);

        advancer.on(new WorkflowRunStartedEvent(runId));

        assertThat(meters.counter("media.late_result_discarded").count()).isZero();
        verify(runStepRepository, never()).failIfRunning(any(), any(), anyShort(), any(), any(), any());
        assertThat(output.getOut().lines().filter(line -> line.contains(" ERROR ")).count()).isEqualTo(1);
        assertThat(output).contains("java.lang.IllegalStateException: database unavailable");
    }

    @Test
    void aResultThatLosesItsCasStillAdvancesTheRun() {
        givenMediaEnabled();
        RunStep step1 = completed(1, UUID.randomUUID(), "{}");
        RunStep step2 = pending(2, "echo");
        givenRun(List.of(def(1, "probe"), def(2, "echo")), List.of(step1, step2));
        when(runStepRepository.findById(step1.getId())).thenReturn(Optional.of(step1));
        when(runStepRepository.completeIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(0);
        when(runStepRepository.claimPending(any(), any(), any(), any())).thenReturn(0);

        advancer.onRemoteCompleted(new MediaJobCompleted(result(step1, MediaResult.Status.COMPLETED, null,
                Map.of("videoCodec", "h264"), null)));

        verify(runStepRepository).claimPending(eq(step2.getId()), any(), any(), any());
    }

    @Test
    void aCheckpointStepPausesTheRunWithThePreviousStepsOutputInsteadOfDispatching() {
        RunStep step1 = completed(1, UUID.randomUUID(), null);
        RunStep checkpoint = pending(2, "APPROVAL_CHECKPOINT");
        givenRun(List.of(def(1, "echo"), def(2, "APPROVAL_CHECKPOINT")), List.of(step1, checkpoint));

        advancer.advance(runId);

        verify(checkpoints).pause(run, checkpoint, null, NOW);
        verify(runStepRepository, never()).claimPending(any(), any(), any(), any());
    }

    @Test
    void anApprovalThatResumesTheCheckpointAdvancesTheEventsRun() {
        RunStep step1 = completed(1, UUID.randomUUID(), "hello");
        RunStep step2 = pending(2, "echo");
        givenRun(List.of(def(1, "echo"), def(2, "echo")), List.of(step1, step2));
        ReviewTaskApprovedEvent event = new ReviewTaskApprovedEvent(UUID.randomUUID(), runId, "hello", null);
        when(checkpoints.resume(event, NOW)).thenReturn(true);

        advancer.onReviewApproved(event);

        verify(runStepRepository).claimPending(eq(step2.getId()), any(), any(), any());
    }

    @Test
    void anApprovalThatDoesNotResumeLeavesTheRunAlone() {
        ReviewTaskApprovedEvent event = new ReviewTaskApprovedEvent(UUID.randomUUID(), runId, "hello", null);
        when(checkpoints.resume(event, NOW)).thenReturn(false);

        advancer.onReviewApproved(event);

        verify(workflowRunRepository, never()).findById(any());
    }

    private void givenMediaEnabled() {
        when(mediaJobs.isEnabled()).thenReturn(true);
        when(mediaJobs.limitsFor("probe")).thenReturn(new MediaLimits(PROBE_QUEUE, PROBE_RUNTIME, 1000L));
    }

    @Test
    void missingRunIsHandledWithoutThrowing() {
        when(workflowRunRepository.findById(runId)).thenReturn(Optional.empty());

        assertThatCode(() -> advancer.on(new WorkflowRunStartedEvent(runId))).doesNotThrowAnyException();

        verify(runStepRepository, never()).claimPending(any(), any(), any(), any());
    }

    @Test
    void anInlineStepIsPickedUpRunCompletedAndTheRunAdvancesToTheNextStep() {
        RunStep step1 = pending(1, "upper");
        RunStep step2 = pending(2, "echo");
        RunStep step1Done = completed(1, step1.getId(), "HELLO");
        RunStep step2Done = completed(2, step2.getId(), "HELLO");
        givenRun(List.of(def(1, "upper"), def(2, "echo")), List.of(step1, step2));
        when(runStepRepository.findByRunIdOrderByStepOrderAsc(runId))
                .thenReturn(List.of(step1, step2), List.of(step1Done, step2), List.of(step1Done, step2Done));
        when(runStepRepository.claimPending(any(), any(), any(), any())).thenReturn(1);
        when(runStepRepository.recordPickup(any(), anyShort(), any(), any(), any(), anyShort())).thenReturn(1);
        when(runStepRepository.completeIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(1);
        when(runStepRepository.countByRunIdAndStatusNot(runId, RunStepStatus.COMPLETED)).thenReturn(1L);

        advancer.on(new WorkflowRunStartedEvent(runId));

        verify(runStepRepository).claimPending(step1.getId(), NOW, NOW.plus(LEASE), "hello");
        verify(runStepRepository).recordPickup(step1.getId(), (short) 1, NOW, NOW.plus(LEASE),
                NOW.plus(Duration.ofSeconds(60)), (short) 0);
        verify(runStepRepository).completeIfRunning(step1.getId(), runId, (short) 1, "HELLO", null, NOW);
        verify(runStepRepository).claimPending(step2.getId(), NOW, NOW.plus(LEASE), "HELLO");
        verify(runStepRepository).completeIfRunning(step2.getId(), runId, (short) 1, "HELLO", null, NOW);
        verify(scheduler, org.mockito.Mockito.times(2))
                .scheduleAtFixedRate(any(), eq(NOW.plusSeconds(30)), eq(Duration.ofSeconds(30)));
    }

    @Test
    void aRunLogsItsStartOnceWithItsStepCountAndThatItCompleted(CapturedOutput output) {
        RunStep step1 = pending(1, "echo");
        givenRun(List.of(def(1, "echo")), List.of(step1));
        when(runStepRepository.findByRunIdOrderByStepOrderAsc(runId))
                .thenReturn(List.of(step1), List.of(completed(1, step1.getId(), "hello")));
        when(runStepRepository.claimPending(any(), any(), any(), any())).thenReturn(1);
        when(runStepRepository.recordPickup(any(), anyShort(), any(), any(), any(), anyShort())).thenReturn(1);
        when(runStepRepository.completeIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(1);
        when(runStepRepository.countByRunIdAndStatusNot(runId, RunStepStatus.COMPLETED)).thenReturn(0L);

        advancer.on(new WorkflowRunStartedEvent(runId));

        assertThat(output).contains("Workflow run started: runId=" + runId + " steps=1")
                .contains("Workflow run finished: runId=" + runId + " status=completed");
        assertThat(output.getOut().lines().filter(line -> line.contains("Workflow run started")).count())
                .isEqualTo(1);
    }

    @Test
    void aRunThatFailsLogsThatItFinishedInError(CapturedOutput output) {
        RunStep step1 = pending(1, "fail");
        givenRun(List.of(def(1, "fail"), def(2, "echo")), List.of(step1, pending(2, "echo")));
        when(runStepRepository.claimPending(any(), any(), any(), any())).thenReturn(1);
        when(runStepRepository.recordPickup(any(), anyShort(), any(), any(), any(), anyShort())).thenReturn(1);
        when(runStepRepository.failIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(1);

        advancer.on(new WorkflowRunStartedEvent(runId));

        assertThat(output).contains("Workflow run started: runId=" + runId + " steps=2")
                .contains("Workflow run finished: runId=" + runId + " status=error");
    }

    @Test
    void aFailingInlineStepErrorsTheStepAndTheRunAndStopsThere(CapturedOutput output) {
        RunStep step1 = pending(1, "fail");
        givenRun(List.of(def(1, "fail"), def(2, "echo")), List.of(step1, pending(2, "echo")));
        when(runStepRepository.claimPending(any(), any(), any(), any())).thenReturn(1);
        when(runStepRepository.recordPickup(any(), anyShort(), any(), any(), any(), anyShort())).thenReturn(1);
        when(runStepRepository.failIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(1);
        when(workflowRunRepository.findById(runId)).thenReturn(Optional.of(run));

        advancer.on(new WorkflowRunStartedEvent(runId));

        verify(runStepRepository).failIfRunning(eq(step1.getId()), eq(runId), eq((short) 1), isNull(),
                contains("Simulated step failure"), eq(NOW));
        assertThat(run.getStatus().name()).isEqualTo("ERROR");
        assertThat(run.getErrorMessage()).contains("Simulated step failure");
        verify(runStepRepository, org.mockito.Mockito.times(1)).claimPending(any(), any(), any(), any());
        assertThat(output).contains("java.lang.IllegalStateException: Simulated step failure")
                .contains("at com.flowcortex.workflow.application.FailStepExecutor.execute");
    }

    @Test
    void aRemoteStepIsEnqueuedInsideTheClaimAndTheAdvanceReturnsWithoutWaiting() {
        givenMediaEnabled();
        RunStep step = new RunStep(UUID.randomUUID(), runId, (short) 1, "probe", UPLOAD_REF);
        givenRun(List.of(def(1, "probe")), List.of(step));
        when(runStepRepository.claimPending(any(), any(), any(), any())).thenReturn(1);

        advancer.on(new WorkflowRunStartedEvent(runId));

        verify(runStepRepository).claimPending(step.getId(), NOW, NOW.plus(PROBE_QUEUE), UPLOAD_REF);
        ArgumentCaptor<MediaJob> job = ArgumentCaptor.forClass(MediaJob.class);
        verify(mediaJobs).enqueue(job.capture());
        assertThat(job.getValue().jobId()).isEqualTo(step.getId());
        assertThat(job.getValue().runId()).isEqualTo(runId);
        assertThat(job.getValue().correlationId()).isEqualTo(runId);
        assertThat(job.getValue().capability()).isEqualTo(MediaCapability.PROBE);
        assertThat(job.getValue().inputs())
                .containsExactly(new MediaJobInput("source", "flowcortex-uploads", "owner/upload"));
        assertThat(job.getValue().outputPrefix()).isEqualTo("runs/" + runId + "/steps/1/attempts/1/");
        assertThat(job.getValue().attempt()).isEqualTo(1);
        assertThat(job.getValue().maxRuntimeSeconds()).isEqualTo(300);
        assertThat(job.getValue().maxInputBytes()).isEqualTo(1000L);
        assertThat(job.getValue().issuedAt()).isEqualTo(NOW);
        assertThat(meters.counter("media.jobs.dispatched").count()).isEqualTo(1.0);
        verify(runStepRepository, never()).recordPickup(any(), anyShort(), any(), any(), any(), anyShort());
        verify(runStepRepository, never()).completeIfRunning(any(), any(), anyShort(), any(), any(), any());
        verify(runStepRepository, never()).failIfRunning(any(), any(), anyShort(), any(), any(), any());
    }

        @Test
        void aCompletedProbeDispatchesExtractAudioWithTheOriginalUploadReference() {
        givenMediaEnabled();
        when(mediaJobs.limitsFor("extract_audio"))
                .thenReturn(new MediaLimits(PROBE_QUEUE, PROBE_RUNTIME, 1000L));

        RunStep probe = new RunStep(UUID.randomUUID(), runId, (short) 1, "probe", UPLOAD_REF);
        probe.markRunning(NOW);
        RunStep completedProbe = new RunStep(probe.getId(), runId, (short) 1, "probe", UPLOAD_REF);
        completedProbe.markRunning(NOW);
        completedProbe.markCompleted(UPLOAD_REF, "{}", NOW);
        RunStep extractAudio = new RunStep(
                UUID.randomUUID(), runId, (short) 2, "extract_audio", UPLOAD_REF);

        givenRun(
                List.of(def(1, "probe"), def(2, "extract_audio")),
                List.of(completedProbe, extractAudio));
        when(runStepRepository.findById(probe.getId())).thenReturn(Optional.of(probe));
        when(runStepRepository.completeIfRunning(any(), any(), anyShort(), any(), any(), any()))
                .thenReturn(1);
        when(runStepRepository.countByRunIdAndStatusNot(runId, RunStepStatus.COMPLETED))
                .thenReturn(1L);
        when(runStepRepository.claimPending(
                extractAudio.getId(), NOW, NOW.plus(PROBE_QUEUE), UPLOAD_REF))
                .thenReturn(1);

        advancer.onRemoteCompleted(new MediaJobCompleted(result(
                probe,
                MediaResult.Status.COMPLETED,
                null,
                Map.of("videoCodec", "h264"),
                null)));

        verify(runStepRepository).claimPending(
                extractAudio.getId(), NOW, NOW.plus(PROBE_QUEUE), UPLOAD_REF);
        ArgumentCaptor<MediaJob> job = ArgumentCaptor.forClass(MediaJob.class);
        verify(mediaJobs).enqueue(job.capture());
        assertThat(job.getValue().jobId()).isEqualTo(extractAudio.getId());
        assertThat(job.getValue().capability()).isEqualTo(MediaCapability.EXTRACT_AUDIO);
        assertThat(job.getValue().inputs())
                .containsExactly(new MediaJobInput("source", "flowcortex-uploads", "owner/upload"));
        assertThat(job.getValue().outputPrefix()).isEqualTo("runs/" + runId + "/steps/2/attempts/1/");
    }

    @Test
    void renderVideoCombinesStoredImagesWithTheAcceptedSpeechOutput() {
        givenMediaEnabled();
        when(mediaJobs.limitsFor("render_video"))
                .thenReturn(new MediaLimits(PROBE_QUEUE, PROBE_RUNTIME, 1000L));
        String images = "[{\"key\":\"image_1\",\"bucket\":\"uploads\",\"objectKey\":\"owner/image\"}]";
        String speechKey = "runs/" + runId + "/steps/3/attempts/1/speech.wav";
        String speechOutputs = "[{\"name\":\"speech.wav\",\"bucket\":\"media\",\"objectKey\":\""
                + speechKey + "\",\"mime\":\"audio/wav\",\"bytes\":42}]";
        RunStep speech = new RunStep(UUID.randomUUID(), runId, (short) 3, "generate_speech");
        speech.markRunning(NOW);
        speech.markCompleted("script", "{\"durationMs\":15000}", NOW, speechOutputs);
        RunStep render = new RunStep(UUID.randomUUID(), runId, (short) 4, "render_video", images);
        WorkflowStep definition = mock(WorkflowStep.class);
        when(definition.getStepOrder()).thenReturn((short) 4);
        when(definition.getConfig()).thenReturn("{\"profile\":\"vertical_ad_v1\",\"outputInputs\":["
                + "{\"stepOrder\":3,\"outputName\":\"speech.wav\",\"inputKey\":\"speech\"}]}");
        givenRun(List.of(definition), List.of(speech, render));
        when(runStepRepository.claimPending(any(), any(), any(), any())).thenReturn(1);

        advancer.advance(runId);

        ArgumentCaptor<MediaJob> job = ArgumentCaptor.forClass(MediaJob.class);
        verify(mediaJobs).enqueue(job.capture());
        assertThat(job.getValue().inputs()).containsExactly(
                new MediaJobInput("image_1", "uploads", "owner/image"),
                new MediaJobInput("speech", "media", speechKey));
    }

    @Test
    void aProviderResultThatBreaksTheJsonSchemaFailsTheStepAtTheTrustBoundaryWithContractViolation(
            CapturedOutput output) {
        givenMediaEnabled();
        RunStep step = pending(1, "generate_script");
        step.markRunning(NOW, NOW.plus(PROBE_QUEUE));
        givenRun(List.of(def(1, "generate_script")), List.of(step));
        when(runStepRepository.findById(step.getId())).thenReturn(Optional.of(step));
        when(runStepRepository.failIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(1);

        advancer.onRemoteCompleted(new MediaJobCompleted(result(step, MediaResult.Status.COMPLETED, null,
                Map.of("profile", "product_ad_v1", "language", "fr", "script", "Bonjour"), null)));

        verify(runStepRepository).failIfRunning(eq(step.getId()), eq(runId), eq((short) 1), eq("contract_violation"),
                contains("Provider generate_script data is not valid"), eq(NOW));
        verify(runStepRepository, never()).completeIfRunning(any(), any(), anyShort(), any(), any(), any());
        assertThat(run.getStatus().name()).isEqualTo("ERROR");
        assertThat(output).contains("Provider result rejected at trust boundary");
    }

    @ParameterizedTest
    @MethodSource("invalidScriptData")
    void providerScriptConstraintsAreEnforcedAfterConversion(Map<String, Object> data, String field) {
        assertProviderDataRejected("generate_script", data, field);
    }

    @ParameterizedTest
    @MethodSource("invalidSpeechData")
    void providerSpeechConstraintsAreEnforcedAfterConversion(Map<String, Object> data, String field) {
        assertProviderDataRejected("generate_speech", data, field);
    }

    @Test
    void aProviderResultThatConformsToTheJsonSchemaIsAcceptedWithoutRejection() {
        givenMediaEnabled();
        RunStep step = pending(1, "generate_script");
        step.markRunning(NOW, NOW.plus(PROBE_QUEUE));
        givenRun(List.of(def(1, "generate_script")), List.of(step));
        when(runStepRepository.findById(step.getId())).thenReturn(Optional.of(step));
        when(runStepRepository.completeIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(1);
        when(runStepRepository.countByRunIdAndStatusNot(runId, RunStepStatus.COMPLETED)).thenReturn(0L);

        advancer.onRemoteCompleted(new MediaJobCompleted(result(step, MediaResult.Status.COMPLETED, null,
                Map.of("profile", "product_ad_v1", "language", "vi", "script",
                        "Khám phá Bình giữ nhiệt. Giữ đồ uống tiện lợi. Đặt mua ngay"), null)));

        verify(runStepRepository, never()).failIfRunning(any(), any(), anyShort(), any(), any(), any());
    }

    @Test
    void speechForTheJobsApprovedRevisionIsAccepted() {
        RunStep step = speechStep("{\"script\":\"Xin chào\",\"sourceRevisionId\":\"" + SPEECH_REVISION + "\"}");
        when(runStepRepository.completeIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(1);
        when(runStepRepository.countByRunIdAndStatusNot(runId, RunStepStatus.COMPLETED)).thenReturn(0L);

        advancer.onRemoteCompleted(new MediaJobCompleted(result(step, MediaResult.Status.COMPLETED, null,
                speechData("format", "wav"), null)));

        verify(runStepRepository).completeIfRunning(eq(step.getId()), eq(runId), eq((short) 1), any(), any(),
                eq(NOW));
        verify(runStepRepository, never()).failIfRunning(any(), any(), anyShort(), any(), any(), any());
    }

    @Test
    void speechForAnotherRevisionThanTheJobsFailsTheStepWithContractViolation() {
        UUID jobRevision = UUID.fromString("0d3b5a3e-55c1-4f0e-9d1c-6f2b7f3d2a10");
        RunStep step = speechStep("{\"script\":\"Xin chào\",\"sourceRevisionId\":\"" + jobRevision + "\"}");
        when(runStepRepository.failIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(1);

        advancer.onRemoteCompleted(new MediaJobCompleted(result(step, MediaResult.Status.COMPLETED, null,
                speechData("format", "wav"), null)));

        verify(runStepRepository).failIfRunning(step.getId(), runId, (short) 1, "contract_violation",
                "Provider generate_speech data is not valid for its schema: sourceRevisionId " + SPEECH_REVISION
                        + " does not match the job's " + jobRevision, NOW);
        verify(runStepRepository, never()).completeIfRunning(any(), any(), anyShort(), any(), any(), any());
    }

    @Test
    void speechForAJobInputWithoutARevisionFailsTheStepWithContractViolation() {
        RunStep step = speechStep("{\"script\":\"Xin chào\"}");
        when(runStepRepository.failIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(1);

        advancer.onRemoteCompleted(new MediaJobCompleted(result(step, MediaResult.Status.COMPLETED, null,
                speechData("format", "wav"), null)));

        verify(runStepRepository).failIfRunning(step.getId(), runId, (short) 1, "contract_violation",
                "Provider generate_speech data is not valid for its schema: sourceRevisionId " + SPEECH_REVISION
                        + " cannot be matched: the job input has no sourceRevisionId", NOW);
        verify(runStepRepository, never()).completeIfRunning(any(), any(), anyShort(), any(), any(), any());
    }

    private RunStep speechStep(String input) {
        givenMediaEnabled();
        RunStep step = new RunStep(UUID.randomUUID(), runId, (short) 1, "generate_speech", input);
        step.markRunning(NOW, NOW.plus(PROBE_QUEUE));
        givenRun(List.of(def(1, "generate_speech")), List.of(step));
        when(runStepRepository.findById(step.getId())).thenReturn(Optional.of(step));
        return step;
    }

    @Test
    void aJobThatBreaksTheContractFailsTheStepWithContractViolationInsteadOfLeavingItPending() {
        givenMediaEnabled();
        RunStep step = new RunStep(UUID.randomUUID(), runId, (short) 1, "probe", UPLOAD_REF);
        givenRun(List.of(def(1, "probe")), List.of(step));
        when(runStepRepository.claimPending(any(), any(), any(), any())).thenReturn(1);
        when(runStepRepository.failIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(1);
        org.mockito.Mockito.doThrow(new ConstraintViolationException("inputs: must not be empty", Set.of()))
                .when(mediaJobs).enqueue(any());

        advancer.on(new WorkflowRunStartedEvent(runId));

        verify(runStepRepository).failIfRunning(eq(step.getId()), eq(runId), eq((short) 1), eq("contract_violation"),
                any(), eq(NOW));
        assertThat(run.getStatus().name()).isEqualTo("ERROR");
    }

    @Test
    void aRemoteStepNeverTakesAnObjectReferenceFromTheRunInput() {
        givenMediaEnabled();
        RunStep step = pending(1, "probe");
        WorkflowRun referenceRun = new WorkflowRun(runId, workflowId, UUID.randomUUID(), UPLOAD_REF, NOW);
        givenRun(List.of(def(1, "probe")), List.of(step));
        when(workflowRunRepository.findById(runId)).thenReturn(Optional.of(referenceRun));
        when(runStepRepository.claimPending(any(), any(), any(), any())).thenReturn(1);
        when(runStepRepository.failIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(1);

        advancer.on(new WorkflowRunStartedEvent(runId));

        verify(mediaJobs, never()).enqueue(any());
        verify(runStepRepository).failIfRunning(eq(step.getId()), eq(runId), eq((short) 1), eq("contract_violation"),
                eq("The step has no input object to process"), eq(NOW));
    }

    @Test
    void aRemoteStepWhileMediaIsDisabledFailsWithMediaDisabledAndEnqueuesNothing() {
        when(mediaJobs.isEnabled()).thenReturn(false);
        when(mediaJobs.limitsFor("probe")).thenReturn(new MediaLimits(PROBE_QUEUE, PROBE_RUNTIME, 1000L));
        RunStep step = pending(1, "probe");
        givenRun(List.of(def(1, "probe")), List.of(step));
        when(runStepRepository.claimPending(any(), any(), any(), any())).thenReturn(1);
        when(runStepRepository.failIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(1);

        advancer.on(new WorkflowRunStartedEvent(runId));

        verify(runStepRepository).failIfRunning(eq(step.getId()), eq(runId), eq((short) 1), eq("media_disabled"),
                any(), eq(NOW));
        verify(mediaJobs, never()).enqueue(any());
    }

    @Test
    void aLostClaimIsANoOp() {
        givenRun(List.of(def(1, "echo")), List.of(pending(1, "echo")));
        when(runStepRepository.claimPending(any(), any(), any(), any())).thenReturn(0);

        advancer.on(new WorkflowRunStartedEvent(runId));

        verify(runStepRepository, never()).recordPickup(any(), anyShort(), any(), any(), any(), anyShort());
        verify(runStepRepository, never()).completeIfRunning(any(), any(), anyShort(), any(), any(), any());
        verify(runStepRepository, never()).failIfRunning(any(), any(), anyShort(), any(), any(), any());
    }

    @Test
    void anUnknownStepTypeErrorsTheStep() {
        RunStep step = pending(1, "nope");
        givenRun(List.of(def(1, "nope")), List.of(step));
        when(runStepRepository.claimPending(any(), any(), any(), any())).thenReturn(1);
        when(runStepRepository.failIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(1);

        advancer.on(new WorkflowRunStartedEvent(runId));

        verify(runStepRepository).failIfRunning(eq(step.getId()), eq(runId), eq((short) 1), isNull(),
                contains("Unsupported step type: nope"), eq(NOW));
    }

    @Test
    void aStepAlreadyInFlightIsNotClaimedAgain() {
        RunStep step = pending(1, "echo");
        step.markRunning(NOW);
        givenRun(List.of(def(1, "echo")), List.of(step));

        advancer.on(new WorkflowRunStartedEvent(runId));

        verify(runStepRepository, never()).claimPending(any(), any(), any(), any());
    }

    @Test
    void aRejectedInlineTaskLeavesTheClaimedStepWithoutPickupForTheReaper(CapturedOutput output) {
        RunAdvancer saturated = advancer(task -> {
            throw new TaskRejectedException("queue full");
        });
        givenRun(List.of(def(1, "echo")), List.of(pending(1, "echo")));
        when(runStepRepository.claimPending(any(), any(), any(), any())).thenReturn(1);

        saturated.on(new WorkflowRunStartedEvent(runId));

        verify(runStepRepository, never()).recordPickup(any(), anyShort(), any(), any(), any(), anyShort());
        verify(runStepRepository, never()).failIfRunning(any(), any(), anyShort(), any(), any(), any());
        assertThat(output).contains("WARN").contains("left for the reaper");
    }

    @Test
    void aCompletedResultBeforeAnyStartedIsApplied(CapturedOutput output) {
        givenMediaEnabled();
        RunStep step = pending(1, "probe");
        step.markRunning(NOW, NOW.plus(PROBE_QUEUE));
        givenRun(List.of(def(1, "probe")), List.of(step));
        when(runStepRepository.findById(step.getId())).thenReturn(Optional.of(step));
        when(runStepRepository.completeIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(1);
        when(runStepRepository.countByRunIdAndStatusNot(runId, RunStepStatus.COMPLETED)).thenReturn(0L);
        MediaOutput details = new MediaOutput("video-details.json", "flowcortex-media",
                "runs/" + runId + "/steps/1/video-details.json", "application/json", 42L, null);

        advancer.onRemoteCompleted(new MediaJobCompleted(result(step, MediaResult.Status.COMPLETED,
                List.of(details), Map.of("videoCodec", "h264"), null)));

        ArgumentCaptor<String> outputs = ArgumentCaptor.forClass(String.class);
        verify(runStepRepository).completeIfRunning(eq(step.getId()), eq(runId), eq((short) 1),
                eq("{\"videoCodec\":\"h264\"}"), outputs.capture(), eq(NOW));
        assertThat(outputs.getValue()).contains("\"name\":\"video-details.json\"").contains("\"bytes\":42");
        assertThat(run.getStatus().name()).isEqualTo("COMPLETED");
        assertThat(output).contains("Job result applied: runId=" + runId + " jobId=" + step.getId()
                + " capability=probe attempt=1 deliveryCount=0 outcome=completed durationMs=n/a");
    }

    @Test
    void anErrorResultFailsTheStepWithTheWorkersCode(CapturedOutput output) {
        givenMediaEnabled();
        RunStep step = pending(1, "probe");
        step.markRunning(NOW, NOW.plus(PROBE_QUEUE));
        step.recordPickup(NOW.minusMillis(2_500), NOW.plus(LEASE), NOW.plus(PROBE_RUNTIME), (short) 0);
        givenRun(List.of(def(1, "probe")), List.of(step));
        when(runStepRepository.findById(step.getId())).thenReturn(Optional.of(step));
        when(runStepRepository.failIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(1);

        advancer.onRemoteCompleted(new MediaJobCompleted(result(step, MediaResult.Status.ERROR, null, null,
                new MediaError(MediaError.Code.PROBE_FAILED, "not a media file", false))));

        verify(runStepRepository).failIfRunning(step.getId(), runId, (short) 1, "probe_failed", "not a media file",
                NOW);
        assertThat(run.getStatus().name()).isEqualTo("ERROR");
        assertThat(output).contains("Job result applied: runId=" + runId + " jobId=" + step.getId()
                + " capability=probe attempt=1 deliveryCount=0 outcome=error code=probe_failed durationMs=2500");
    }

    @Test
    void aLateCompletedResultAfterLeaseExpiryIsDiscardedLoggedAndCounted(CapturedOutput output) {
        RunStep step = pending(1, "probe");
        step.markRunning(NOW);
        step.markError(null, "lease_expired", "No heartbeat within the lease", NOW);
        givenRun(List.of(def(1, "probe")), List.of(step));
        when(runStepRepository.completeIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(0);
        when(runStepRepository.findById(step.getId())).thenReturn(Optional.of(step));

        advancer.onRemoteCompleted(new MediaJobCompleted(result(step, MediaResult.Status.COMPLETED, null,
                Map.of("videoCodec", "h264"), null)));

        assertThat(meters.counter("media.late_result_discarded").count()).isEqualTo(1.0);
        assertThat(output).contains("WARN").contains("discarded").contains("the step is error (lease_expired)");
        assertThat(run.getStatus().name()).isEqualTo("RUNNING");
        verify(runStepRepository, never()).claimPending(any(), any(), any(), any());
    }

    @Test
    void aRedeliverysStartedRestartsTheRunDeadline() {
        givenMediaEnabled();
        RunStep step = pending(1, "probe");
        step.markRunning(NOW, NOW.plus(PROBE_QUEUE));
        when(runStepRepository.findById(step.getId())).thenReturn(Optional.of(step));
        Instant startedAt = NOW.minusSeconds(5);

        advancer.onRemoteProgress(new MediaJobProgressed(progress(step, MediaProgress.Kind.STARTED, 2, null,
                startedAt)));

        verify(runStepRepository).recordPickup(step.getId(), (short) 1, startedAt, startedAt.plus(LEASE),
                startedAt.plus(PROBE_RUNTIME), (short) 2);
    }

    @Test
    void aHeartbeatExtendsTheLeaseWithoutRestartingTheRunDeadline(CapturedOutput output) {
        givenMediaEnabled();
        RunStep step = pending(1, "probe");
        step.markRunning(NOW, NOW.plus(PROBE_QUEUE));
        when(runStepRepository.findById(step.getId())).thenReturn(Optional.of(step));
        when(runStepRepository.extendLease(any(), anyShort(), any(), any(), any(), anyShort(), any())).thenReturn(1);

        advancer.onRemoteProgress(new MediaJobProgressed(progress(step, MediaProgress.Kind.HEARTBEAT, 2, 40,
                NOW.minusSeconds(1))));

        verify(runStepRepository).extendLease(step.getId(), (short) 1, NOW, NOW.plus(LEASE), (short) 40, (short) 2,
                NOW.plus(PROBE_RUNTIME));
        verify(runStepRepository, never()).recordPickup(any(), anyShort(), any(), any(), any(), anyShort());
        assertThat(output).contains("Job progress: runId=" + runId + " jobId=" + step.getId()
                + " capability=probe attempt=1 deliveryCount=2 percent=40");
    }

    private void assertProviderDataRejected(String capability, Map<String, Object> data, String field) {
        RunStep step = pending(1, capability);
        step.markRunning(NOW, NOW.plus(PROBE_QUEUE));
        givenRun(List.of(def(1, capability)), List.of(step));
        when(runStepRepository.findById(step.getId())).thenReturn(Optional.of(step));
        when(runStepRepository.failIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(1);

        advancer.onRemoteCompleted(new MediaJobCompleted(
                result(step, MediaResult.Status.COMPLETED, null, data, null)));

        verify(runStepRepository).failIfRunning(eq(step.getId()), eq(runId), eq((short) 1),
                eq("contract_violation"), contains(field), eq(NOW));
        verify(runStepRepository, never()).completeIfRunning(any(), any(), anyShort(), any(), any(), any());
    }

    private static Stream<Arguments> invalidScriptData() {
        return Stream.of(
                Arguments.of(Map.of("profile", "wrong", "language", "vi", "script", "Hợp lệ"), "profile"),
                Arguments.of(Map.of("profile", "product_ad_v1", "language", "fr", "script", "Bonjour"),
                        "language"),
                Arguments.of(Map.of("profile", "product_ad_v1", "language", "vi", "script", ""), "script"),
                Arguments.of(Map.of("profile", "product_ad_v1", "language", "vi",
                        "script", "a".repeat(8001)), "script"));
    }

    private static Stream<Arguments> invalidSpeechData() {
        return Stream.of(
                Arguments.of(speechData("profile", "wrong"), "profile"),
                Arguments.of(speechData("durationMs", 0), "durationMs"));
    }

    private static Map<String, Object> speechData(String field, Object value) {
        Map<String, Object> data = new HashMap<>(Map.of(
                "profile", "vi_voice_v1",
                "durationMs", 15_000,
                "provider", "edge",
                "voice", "vi-VN-HoaiMyNeural",
                "speed", "+0%",
                "sourceRevisionId", UUID.fromString("a71ea3ba-d72a-4c2c-985c-3a7fd98853c4"),
                "idempotencyKey", "a".repeat(64),
                "format", "wav"));
        data.put(field, value);
        return data;
    }

    private MediaResult result(RunStep step, MediaResult.Status status, List<MediaOutput> outputs,
            Map<String, Object> data, MediaError error) {
        return new MediaResult("1", step.getId(), runId, 1, 0, status, outputs, data, error, NOW, "worker-1");
    }

    private MediaProgress progress(RunStep step, MediaProgress.Kind kind, int deliveryCount, Integer percent,
            Instant at) {
        return new MediaProgress("1", step.getId(), runId, 1, deliveryCount, kind, percent, null, at);
    }
}
