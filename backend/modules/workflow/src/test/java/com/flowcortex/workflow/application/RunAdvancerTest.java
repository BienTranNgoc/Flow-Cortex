package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
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
import com.flowcortex.credit.api.CreditAccounts;
import com.flowcortex.workflow.application.provider.ProviderStepPool;
import com.flowcortex.workflow.application.vendor.VendorJobLedger;
import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.domain.RunStepStatus;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
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
import org.springframework.context.ApplicationEventPublisher;
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
    private final MediaJobs mediaJobs = mock(MediaJobs.class);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final NestedStepExecutor nested = new NestedStepExecutor();
    private final CaptureStepExecutor capture = new CaptureStepExecutor();
        private final StepExecutorRegistry registry = new StepExecutorRegistry(List.of(
                new EchoStepExecutor(),
                new FailStepExecutor(),
                new UpperStepExecutor(),
                new ProviderStepExecutor(),
                new StoringStepExecutor(),
                new VoiceStepExecutor(),
                new IterateStepExecutor(runStepRepository),
                capture,
                nested,
                new RemoteStepExecutor(MediaCapability.PROBE.value(), mediaJobs, clock),
                new RemoteStepExecutor(MediaCapability.EXTRACT_AUDIO.value(), mediaJobs, clock),
                new RemoteStepExecutor(MediaCapability.RENDER_VIDEO.value(), mediaJobs, clock)));
    private final CreditAccounts credits = mock(CreditAccounts.class);
    private final RunStepTransactions transactions =
            new RunStepTransactions(runStepRepository, workflowRunRepository, credits,
                    mock(RunUpdates.class), mock(ApplicationEventPublisher.class), mock(ChildRuns.class));
    private final ReviewCheckpointTransactions checkpoints = mock(ReviewCheckpointTransactions.class);
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private RunAdvancer advancer(TaskExecutor taskExecutor) {
        return advancer(taskExecutor, Runnable::run);
    }

    private RunAdvancer advancer(TaskExecutor taskExecutor, ProviderStepPool providerPool) {
        return new RunAdvancer(workflowRunRepository, runStepRepository, transactions, new InputResolver(), registry,
                taskExecutor, providerPool, scheduler, Tracer.NOOP, ObservationRegistry.NOOP, meters, LEASE, clock,
                checkpoints, validator);
    }

    private final RunAdvancer advancer = advancer(Runnable::run);

    private final VendorJobLedger ledger = mock(VendorJobLedger.class);
    private final RunAdvancer withLedger = new RunAdvancer(workflowRunRepository, runStepRepository, transactions,
            new InputResolver(), registry, Runnable::run, Runnable::run, scheduler, Tracer.NOOP,
            ObservationRegistry.NOOP, meters, LEASE, clock, checkpoints, validator, ledger);

    private final UUID runId = UUID.randomUUID();
    private final UUID workflowId = UUID.randomUUID();
    private final WorkflowRun run = new WorkflowRun(runId, workflowId, UUID.randomUUID(), "hello", NOW);

    private void givenRun(List<RunStep> steps) {
        when(workflowRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(runStepRepository.findByRunIdOrderByStepOrderAsc(runId)).thenReturn(steps);
        when(scheduler.scheduleAtFixedRate(any(), any(Instant.class), any(Duration.class)))
                .thenAnswer(invocation -> mock(ScheduledFuture.class));
    }

    private RunStep pending(int order, String type) {
        return new RunStep(UUID.randomUUID(), runId, (short) order, type);
    }

    /** What a result reads about its step before closing it. */
    private void givenTiming(RunStep step) {
        when(runStepRepository.findTimingById(step.getId())).thenReturn(Optional.of(new RunStepRepository.Timing() {
            @Override
            public String getType() {
                return step.getType();
            }

            @Override
            public short getStepOrder() {
                return step.getStepOrder();
            }

            @Override
            public String getInput() {
                return step.getInput();
            }

            @Override
            public Instant getPickedUpAt() {
                return step.getPickedUpAt();
            }
        }));
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

    /** A nested type: starts its child in the claim, so what it saw there is recorded for the tests. */
    private final class NestedStepExecutor implements StepExecutor {

        static final Duration MAX_RUNTIME = Duration.ofHours(2);

        final List<Thread> threads = new java.util.ArrayList<>();
        boolean claimedBeforeExecute;
        boolean pickedUpBeforeExecute;
        RuntimeException failure;

        @Override
        public String type() {
            return "nested";
        }

        @Override
        public Mode mode() {
            return Mode.NESTED;
        }

        @Override
        public Duration maxRuntime() {
            return MAX_RUNTIME;
        }

        @Override
        public String execute(StepContext context) {
            threads.add(Thread.currentThread());
            claimedBeforeExecute = invoked("claimPending");
            pickedUpBeforeExecute = invoked("recordPickup");
            if (failure != null) {
                throw failure;
            }
            return null;
        }

        private boolean invoked(String method) {
            return org.mockito.Mockito.mockingDetails(runStepRepository).getInvocations().stream()
                    .anyMatch(invocation -> invocation.getMethod().getName().equals(method));
        }
    }

    /** An inline type that waits on a provider, so it runs on the provider pool and waits longer for pickup. */
    private static final class ProviderStepExecutor implements StepExecutor {

        @Override
        public String type() {
            return "slow";
        }

        @Override
        public Mode mode() {
            return Mode.INLINE;
        }

        @Override
        public Pool pool() {
            return Pool.PROVIDER;
        }

        @Override
        public Duration queueTimeout(Duration lease) {
            return Duration.ofMinutes(20);
        }

        @Override
        public Duration maxRuntime() {
            return Duration.ofMinutes(15);
        }

        @Override
        public String execute(StepContext context) {
            return context.input();
        }
    }

    /** An inline type that stores one object for its own step, as an inline step that writes files does. */
    private static final class StoringStepExecutor implements StepExecutor {

        @Override
        public String type() {
            return "store";
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
            context.storedOutputs().add(new MediaOutput("voice.wav", "flowcortex-uploads",
                    "runs/" + context.runId() + "/steps/" + context.stepOrder() + "/voice.wav", "audio/wav", 3L, null));
            return "{\"voiced\":1}";
        }
    }

    /** An inline type whose output is the voice its run was started with. */
    private static final class VoiceStepExecutor implements StepExecutor {

        @Override
        public String type() {
            return "voice";
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
            return context.options().voice();
        }
    }

    /** A completed remote step that recorded {@code objects} ({@code name}, {@code bucket}, {@code objectKey} triples). */
    private RunStep completedWithObjects(int order, String... objects) {
        StringBuilder outputs = new StringBuilder("[");
        for (int i = 0; i < objects.length; i += 3) {
            outputs.append(i == 0 ? "" : ",").append("{\"name\":\"").append(objects[i]).append("\",\"bucket\":\"")
                    .append(objects[i + 1]).append("\",\"objectKey\":\"").append(objects[i + 2])
                    .append("\",\"mime\":\"application/octet-stream\",\"bytes\":1}");
        }
        RunStep step = new RunStep(UUID.randomUUID(), runId, (short) order, "probe", UPLOAD_REF);
        step.markRunning(NOW);
        step.markCompleted(UPLOAD_REF, "{}", NOW, outputs.append("]").toString());
        return step;
    }

    private void givenExtractAudioLimits() {
        givenMediaEnabled();
        when(mediaJobs.limitsFor("extract_audio")).thenReturn(new MediaLimits(PROBE_QUEUE, PROBE_RUNTIME, 1000L));
    }

    @Test
    void anObjectAnInlineStepStoredReachesALaterRemoteStepThroughItsOutputInputs() {
        givenExtractAudioLimits();
        String voiceKey = "runs/" + runId + "/steps/1/voice.wav";
        RunStep voiced = completedWithObjects(1, "voice.wav", "flowcortex-uploads", voiceKey);
        RunStep compose = new RunStep(UUID.randomUUID(), runId, (short) 2, "extract_audio", UPLOAD_REF,
                "{\"label\":\"AI\",\"outputInputs\":["
                        + "{\"stepOrder\":1,\"outputName\":\"voice.wav\",\"inputKey\":\"voice\"}]}", null);
        givenRun(List.of(voiced, compose));
        when(runStepRepository.claimPending(any(), any(), any(), any())).thenReturn(1);

        advancer.advance(runId);

        ArgumentCaptor<MediaJob> job = ArgumentCaptor.forClass(MediaJob.class);
        verify(mediaJobs).enqueue(job.capture());
        assertThat(job.getValue().inputs()).containsExactly(
                new MediaJobInput("source", "flowcortex-uploads", "owner/upload"),
                new MediaJobInput("voice", "flowcortex-uploads", voiceKey));
    }

    @Test
    void aDeclaredObjectTheEarlierStepDidNotRecordFailsTheStepWithContractViolationAndPublishesNothing() {
        givenExtractAudioLimits();
        List<RunStep> steps = new java.util.ArrayList<>();
        for (int order = 1; order <= 5; order++) {
            steps.add(completedWithObjects(order));
        }
        steps.add(completedWithObjects(6, "vocals.wav", "flowcortex-media", "runs/" + runId + "/steps/6/vocals.wav"));
        RunStep compose = new RunStep(UUID.randomUUID(), runId, (short) 7, "extract_audio", UPLOAD_REF,
                "{\"outputInputs\":[{\"stepOrder\":6,\"outputName\":\"accompaniment.wav\",\"inputKey\":\"music\"}]}",
                null);
        steps.add(compose);
        givenRun(steps);
        when(runStepRepository.claimPending(any(), any(), any(), any())).thenReturn(1);
        when(runStepRepository.failIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(1);

        advancer.advance(runId);

        verify(runStepRepository).failIfRunning(compose.getId(), runId, (short) 1, "contract_violation",
                "Step 6 must have exactly one output named accompaniment.wav", NOW);
        verify(mediaJobs, never()).enqueue(any());
        assertThat(run.getStatus().name()).isEqualTo("ERROR");
    }

    @Test
    void anInlineStepRecordsTheObjectsItStoredAsItsOutputsLikeARemoteStep() {
        RunStep step = pending(1, "store");
        givenRun(List.of(step));
        when(runStepRepository.findByRunIdOrderByStepOrderAsc(runId))
                .thenReturn(List.of(step), List.of(completed(1, step.getId(), "{\"voiced\":1}")));
        when(runStepRepository.claimPending(any(), any(), any(), any())).thenReturn(1);
        when(runStepRepository.recordPickup(any(), anyShort(), any(), any(), any(), anyShort())).thenReturn(1);
        when(runStepRepository.completeIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(1);
        when(runStepRepository.countTopLevelNotCompleted(runId)).thenReturn(1L);

        advancer.advance(runId);

        verify(runStepRepository).completeIfRunning(step.getId(), runId, (short) 1, "{\"voiced\":1}",
                "[{\"name\":\"voice.wav\",\"bucket\":\"flowcortex-uploads\",\"objectKey\":\"runs/" + runId
                        + "/steps/1/voice.wav\",\"mime\":\"audio/wav\",\"bytes\":3}]", NOW);
    }

    @Test
    void anInlineStepReadsTheVoiceItsRunWasStartedWith() {
        WorkflowRun maleRun = new WorkflowRun(runId, workflowId, UUID.randomUUID(), "clip.mp4",
                "{\"voice\":\"male\"}", NOW);
        RunStep step = pending(1, "voice");
        givenRun(List.of(step));
        when(runStepRepository.findByRunIdOrderByStepOrderAsc(runId))
                .thenReturn(List.of(step), List.of(completed(1, step.getId(), "male")));
        when(workflowRunRepository.findById(runId)).thenReturn(Optional.of(maleRun));
        when(runStepRepository.claimPending(any(), any(), any(), any())).thenReturn(1);
        when(runStepRepository.recordPickup(any(), anyShort(), any(), any(), any(), anyShort())).thenReturn(1);
        when(runStepRepository.completeIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(1);
        when(runStepRepository.countTopLevelNotCompleted(runId)).thenReturn(1L);

        advancer.advance(runId);

        verify(runStepRepository).completeIfRunning(step.getId(), runId, (short) 1, "male", null, NOW);
    }

    @Test
    void anExceptionWhileAdvancingToTheNextStepIsLoggedOnceAsAnErrorNotCountedAsALateResult(
            CapturedOutput output) {
        RunStep step1 = pending(1, "echo");
        RunStep step2 = pending(2, "echo");
        givenRun(List.of(step1, step2));
        when(runStepRepository.findByRunIdOrderByStepOrderAsc(runId))
                .thenReturn(List.of(step1, step2), List.of(completed(1, step1.getId(), "hello"), step2));
        when(runStepRepository.claimPending(eq(step1.getId()), any(), any(), any())).thenReturn(1);
        when(runStepRepository.claimPending(eq(step2.getId()), any(), any(), any()))
                .thenThrow(new IllegalStateException("database unavailable"));
        when(runStepRepository.recordPickup(any(), anyShort(), any(), any(), any(), anyShort())).thenReturn(1);
        when(runStepRepository.completeIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(1);
        when(runStepRepository.countTopLevelNotCompleted(runId)).thenReturn(1L);

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
        givenRun(List.of(step1, step2));
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
        givenRun(List.of(step1, checkpoint));

        advancer.advance(runId);

        verify(checkpoints).pause(run, checkpoint, null, NOW);
        verify(runStepRepository, never()).claimPending(any(), any(), any(), any());
    }

    @Test
    void anApprovalThatResumesTheCheckpointAdvancesTheEventsRun() {
        RunStep step1 = completed(1, UUID.randomUUID(), "hello");
        RunStep step2 = pending(2, "echo");
        givenRun(List.of(step1, step2));
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
        givenRun(List.of(step1, step2));
        when(runStepRepository.findByRunIdOrderByStepOrderAsc(runId))
                .thenReturn(List.of(step1, step2), List.of(step1Done, step2), List.of(step1Done, step2Done));
        when(runStepRepository.claimPending(any(), any(), any(), any())).thenReturn(1);
        when(runStepRepository.recordPickup(any(), anyShort(), any(), any(), any(), anyShort())).thenReturn(1);
        when(runStepRepository.completeIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(1);
        when(runStepRepository.countTopLevelNotCompleted(runId)).thenReturn(1L);

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
        givenRun(List.of(step1));
        when(runStepRepository.findByRunIdOrderByStepOrderAsc(runId))
                .thenReturn(List.of(step1), List.of(completed(1, step1.getId(), "hello")));
        when(runStepRepository.claimPending(any(), any(), any(), any())).thenReturn(1);
        when(runStepRepository.recordPickup(any(), anyShort(), any(), any(), any(), anyShort())).thenReturn(1);
        when(runStepRepository.completeIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(1);
        when(runStepRepository.countTopLevelNotCompleted(runId)).thenReturn(0L);

        advancer.on(new WorkflowRunStartedEvent(runId));

        assertThat(output).contains("Workflow run started: runId=" + runId + " steps=1")
                .contains("Workflow run finished: runId=" + runId + " status=completed");
        assertThat(output.getOut().lines().filter(line -> line.contains("Workflow run started")).count())
                .isEqualTo(1);
    }

    @Test
    void aRunThatFailsLogsThatItFinishedInError(CapturedOutput output) {
        RunStep step1 = pending(1, "fail");
        givenRun(List.of(step1, pending(2, "echo")));
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
        givenRun(List.of(step1, pending(2, "echo")));
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
    void anInlineStepRecordsOneEventWithItsOutcomeErrorCodeAndDuration(CapturedOutput output) {
        RunStep step1 = pending(1, "echo");
        RunStep step2 = pending(2, "fail");
        givenRun(List.of(step1, step2));
        when(runStepRepository.findByRunIdOrderByStepOrderAsc(runId))
                .thenReturn(List.of(step1, step2), List.of(completed(1, step1.getId(), "hello"), step2));
        when(runStepRepository.claimPending(any(), any(), any(), any())).thenReturn(1);
        when(runStepRepository.recordPickup(any(), anyShort(), any(), any(), any(), anyShort())).thenReturn(1);
        when(runStepRepository.completeIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(1);
        when(runStepRepository.failIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(1);
        when(runStepRepository.countTopLevelNotCompleted(runId)).thenReturn(1L);

        advancer.on(new WorkflowRunStartedEvent(runId));

        assertThat(output).contains("Step finished: runId=" + runId + " runStepId=" + step1.getId()
                + " stepOrder=1 type=echo outcome=completed durationMs=0")
                .contains("Step finished: runId=" + runId + " runStepId=" + step2.getId()
                        + " stepOrder=2 type=fail outcome=error code=null durationMs=0");
    }

    @Test
    void aNestedStepRunsItsExecutorInsideTheClaim() {
        RunStep step = pending(1, "nested");
        givenRun(List.of(step));
        when(runStepRepository.claimPending(any(), any(), any(), any())).thenReturn(1);
        when(runStepRepository.recordPickup(any(), anyShort(), any(), any(), any(), anyShort())).thenReturn(1);
        org.springframework.core.task.TaskExecutor taskExecutor = mock(org.springframework.core.task.TaskExecutor.class);
        List<Runnable> providerTasks = new java.util.ArrayList<>();

        advancer(taskExecutor, providerTasks::add).on(new WorkflowRunStartedEvent(runId));

        assertThat(nested.threads).containsExactly(Thread.currentThread());
        assertThat(nested.claimedBeforeExecute).isTrue();
        assertThat(nested.pickedUpBeforeExecute).isFalse();
        verify(taskExecutor, never()).execute(any());
        assertThat(providerTasks).isEmpty();
        verify(runStepRepository, never()).completeIfRunning(any(), any(), anyShort(), any(), any(), any());
    }

    @Test
    void aNestedStepRecordsItsDeadlineRightAfterTheClaim() {
        RunStep step = pending(1, "nested");
        givenRun(List.of(step));
        when(runStepRepository.claimPending(any(), any(), any(), any())).thenReturn(1);
        when(runStepRepository.recordPickup(any(), anyShort(), any(), any(), any(), anyShort())).thenReturn(1);
        Instant runDeadline = NOW.plus(NestedStepExecutor.MAX_RUNTIME);

        advancer.on(new WorkflowRunStartedEvent(runId));

        verify(runStepRepository).recordPickup(step.getId(), (short) 1, NOW, runDeadline.plus(LEASE), runDeadline,
                (short) 0);
        verify(scheduler, never()).scheduleAtFixedRate(any(), any(Instant.class), any(Duration.class));
    }

    @Test
    void aNestedStepWhoseDeadlineWriteFindsItClosedAlreadyIsLeftAlone() {
        RunStep step = pending(1, "nested");
        givenRun(List.of(step));
        when(runStepRepository.claimPending(any(), any(), any(), any())).thenReturn(1);
        when(runStepRepository.recordPickup(any(), anyShort(), any(), any(), any(), anyShort())).thenReturn(0);

        advancer.on(new WorkflowRunStartedEvent(runId));

        verify(runStepRepository, never()).failIfRunning(any(), any(), anyShort(), any(), any(), any());
        verify(runStepRepository, never()).completeIfRunning(any(), any(), anyShort(), any(), any(), any());
    }

    @Test
    void aNestedStepFailingInTheClaimIsNotPickedUp() {
        RunStep step = pending(1, "nested");
        givenRun(List.of(step));
        when(runStepRepository.claimPending(any(), any(), any(), any())).thenReturn(1);
        when(runStepRepository.failIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(1);
        nested.failure = new StepFailedException("workflow_call_cycle", "no");

        advancer.on(new WorkflowRunStartedEvent(runId));

        verify(runStepRepository).failIfRunning(eq(step.getId()), eq(runId), eq((short) 1),
                eq("workflow_call_cycle"), eq("no"), eq(NOW));
        verify(runStepRepository, never()).recordPickup(any(), anyShort(), any(), any(), any(), anyShort());
    }

    @Test
    void aNestedStepIsNotReportedRemote() {
        assertThat(registry.isRemote("nested")).isFalse();
        assertThat(registry.remoteTypes()).doesNotContain("nested");
    }

    @Test
    void aRemoteStepIsEnqueuedInsideTheClaimAndTheAdvanceReturnsWithoutWaiting() {
        givenMediaEnabled();
        RunStep step = new RunStep(UUID.randomUUID(), runId, (short) 1, "probe", UPLOAD_REF);
        givenRun(List.of(step));
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

        givenRun(List.of(completedProbe, extractAudio));
        givenTiming(probe);
        when(runStepRepository.completeIfRunning(any(), any(), anyShort(), any(), any(), any()))
                .thenReturn(1);
        when(runStepRepository.countTopLevelNotCompleted(runId))
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
        speech.markCompleted("script", "{\"durationMs\":9000}", NOW, speechOutputs);
        RunStep render = new RunStep(UUID.randomUUID(), runId, (short) 4, "render_video", images,
                "{\"profile\":\"vertical_ad_v1\",\"outputInputs\":["
                        + "{\"stepOrder\":3,\"outputName\":\"speech.wav\",\"inputKey\":\"speech\"}]}",
                null);
        givenRun(List.of(speech, render));
        when(runStepRepository.claimPending(any(), any(), any(), any())).thenReturn(1);

        advancer.advance(runId);

        ArgumentCaptor<MediaJob> job = ArgumentCaptor.forClass(MediaJob.class);
        verify(mediaJobs).enqueue(job.capture());
        assertThat(job.getValue().inputs()).containsExactly(
                new MediaJobInput("image_1", "uploads", "owner/image"),
                new MediaJobInput("speech", "media", speechKey));
    }

    @Test
    void aStepResolvesItsInputFromTheReferenceItsRunStartedWith() {
        RunStep first = completed(1, UUID.randomUUID(), "FIRST");
        RunStep second = completed(2, UUID.randomUUID(), "SECOND");
        RunStep third = new RunStep(UUID.randomUUID(), runId, (short) 3, "echo", null, null, "{step:1}");
        givenRun(List.of(first, second, third));
        when(runStepRepository.claimPending(any(), any(), any(), any())).thenReturn(1);

        advancer.advance(runId);

        verify(runStepRepository).claimPending(third.getId(), NOW, NOW.plus(LEASE), "FIRST");
    }

    @Test
    void aProviderResultThatBreaksTheJsonSchemaFailsTheStepAtTheTrustBoundaryWithContractViolation(
            CapturedOutput output) {
        givenMediaEnabled();
        RunStep step = pending(1, "generate_script");
        step.markRunning(NOW, NOW.plus(PROBE_QUEUE));
        givenRun(List.of(step));
        givenTiming(step);
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
        givenRun(List.of(step));
        givenTiming(step);
        when(runStepRepository.completeIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(1);
        when(runStepRepository.countTopLevelNotCompleted(runId)).thenReturn(0L);

        advancer.onRemoteCompleted(new MediaJobCompleted(result(step, MediaResult.Status.COMPLETED, null,
                Map.of("profile", "product_ad_v1", "language", "vi", "script",
                        "Khám phá Bình giữ nhiệt. Giữ đồ uống tiện lợi. Đặt mua ngay"), null)));

        verify(runStepRepository, never()).failIfRunning(any(), any(), anyShort(), any(), any(), any());
    }

    @Test
    void speechForTheJobsApprovedRevisionIsAccepted() {
        RunStep step = speechStep("{\"script\":\"Xin chào\",\"sourceRevisionId\":\"" + SPEECH_REVISION + "\"}");
        when(runStepRepository.completeIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(1);
        when(runStepRepository.countTopLevelNotCompleted(runId)).thenReturn(0L);

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

    @Test
    void speechExactlyAsLongAsTheVideoIsAccepted() {
        RunStep step = speechStep("{\"script\":\"Xin chào\",\"sourceRevisionId\":\"" + SPEECH_REVISION + "\"}");
        when(runStepRepository.completeIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(1);
        when(runStepRepository.countTopLevelNotCompleted(runId)).thenReturn(0L);

        advancer.onRemoteCompleted(new MediaJobCompleted(result(step, MediaResult.Status.COMPLETED, null,
                speechData("durationMs", 10_000), null)));

        verify(runStepRepository).completeIfRunning(eq(step.getId()), eq(runId), eq((short) 1), any(), any(),
                eq(NOW));
        verify(runStepRepository, never()).failIfRunning(any(), any(), anyShort(), any(), any(), any());
    }

    @Test
    void speechLongerThanTheVideoFailsTheStepWithSpeechTooLong() {
        RunStep step = speechStep("{\"script\":\"Xin chào\",\"sourceRevisionId\":\"" + SPEECH_REVISION + "\"}");
        when(runStepRepository.failIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(1);

        advancer.onRemoteCompleted(new MediaJobCompleted(result(step, MediaResult.Status.COMPLETED, null,
                speechData("durationMs", 10_001), null)));

        verify(runStepRepository).failIfRunning(step.getId(), runId, (short) 1, "speech_too_long",
                "The approved script reads for 10.1 s, longer than the 10 s video. "
                        + "Start a new run with a shorter script.", NOW);
        verify(runStepRepository, never()).completeIfRunning(any(), any(), anyShort(), any(), any(), any());
    }

    @Test
    void theSpeechTooLongMessageStatesTheSpokenLengthToATenthOfASecond() {
        RunStep step = speechStep("{\"script\":\"Xin chào\",\"sourceRevisionId\":\"" + SPEECH_REVISION + "\"}");
        when(runStepRepository.failIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(1);

        advancer.onRemoteCompleted(new MediaJobCompleted(result(step, MediaResult.Status.COMPLETED, null,
                speechData("durationMs", 13_400), null)));

        verify(runStepRepository).failIfRunning(step.getId(), runId, (short) 1, "speech_too_long",
                "The approved script reads for 13.4 s, longer than the 10 s video. "
                        + "Start a new run with a shorter script.", NOW);
    }

    @Test
    void aCompletedVideoResultConsumesItsLedgerRowOnceAfterTheStepCompleted() {
        RunStep step = videoStep();
        when(runStepRepository.completeIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(1);
        Map<String, Object> data = videoData();

        withLedger.onRemoteCompleted(new MediaJobCompleted(result(step, MediaResult.Status.COMPLETED, null, data,
                null)));

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(runStepRepository, ledger);
        order.verify(runStepRepository).completeIfRunning(eq(step.getId()), eq(runId), eq((short) 1), any(), any(),
                eq(NOW));
        order.verify(ledger).consume(runId, step.getId(), "generate_video", data);
        verify(ledger, org.mockito.Mockito.times(1)).consume(any(), any(), any(), any());
    }

    @Test
    void aVideoResultThatLostTheCasNeverConsumes() {
        RunStep step = videoStep();
        when(runStepRepository.completeIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(0);

        withLedger.onRemoteCompleted(new MediaJobCompleted(result(step, MediaResult.Status.COMPLETED, null,
                videoData(), null)));

        verify(ledger, never()).consume(any(), any(), any(), any());
    }

    @Test
    void aConsumeThatFailsTwiceLeavesTheStepCompleted(CapturedOutput output) {
        RunStep step = videoStep();
        when(runStepRepository.completeIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(1);
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"))
                .when(ledger).consume(any(), any(), any(), any());

        assertThatCode(() -> withLedger.onRemoteCompleted(new MediaJobCompleted(result(step,
                MediaResult.Status.COMPLETED, null, videoData(), null)))).doesNotThrowAnyException();

        verify(ledger, org.mockito.Mockito.times(2)).consume(any(), any(), any(), any());
        verify(runStepRepository, never()).failIfRunning(any(), any(), anyShort(), any(), any(), any());
        assertThat(output).contains("outcome=consume_failed");
    }

    private RunStep videoStep() {
        givenMediaEnabled();
        RunStep step = new RunStep(UUID.randomUUID(), runId, (short) 1, "generate_video", "{}");
        step.markRunning(NOW, NOW.plus(PROBE_QUEUE));
        givenRun(List.of(step));
        givenTiming(step);
        return step;
    }

    private static Map<String, Object> videoData() {
        Map<String, Object> data = new HashMap<>();
        data.put("profile", "vertical_ad_clip_v1");
        data.put("durationSeconds", 5);
        data.put("provider", "seedance");
        data.put("model", "seedance-1");
        data.put("aspectRatio", "9:16");
        data.put("resolution", "720p");
        data.put("sourceRevisionId", UUID.randomUUID());
        data.put("idempotencyKey", "a".repeat(64));
        data.put("providerTaskId", "cgt-1");
        data.put("format", "mp4");
        data.put("fingerprint", "b".repeat(64));
        data.put("requestKey", "c".repeat(64));
        return data;
    }

    private RunStep speechStep(String input) {
        givenMediaEnabled();
        RunStep step = new RunStep(UUID.randomUUID(), runId, (short) 1, "generate_speech", input);
        step.markRunning(NOW, NOW.plus(PROBE_QUEUE));
        givenRun(List.of(step));
        givenTiming(step);
        return step;
    }

    @Test
    void aJobThatBreaksTheContractFailsTheStepWithContractViolationInsteadOfLeavingItPending() {
        givenMediaEnabled();
        RunStep step = new RunStep(UUID.randomUUID(), runId, (short) 1, "probe", UPLOAD_REF);
        givenRun(List.of(step));
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
        givenRun(List.of(step));
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
        givenRun(List.of(step));
        when(runStepRepository.claimPending(any(), any(), any(), any())).thenReturn(1);
        when(runStepRepository.failIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(1);

        advancer.on(new WorkflowRunStartedEvent(runId));

        verify(runStepRepository).failIfRunning(eq(step.getId()), eq(runId), eq((short) 1), eq("media_disabled"),
                any(), eq(NOW));
        verify(mediaJobs, never()).enqueue(any());
    }

    @Test
    void aLostClaimIsANoOp() {
        givenRun(List.of(pending(1, "echo")));
        when(runStepRepository.claimPending(any(), any(), any(), any())).thenReturn(0);

        advancer.on(new WorkflowRunStartedEvent(runId));

        verify(runStepRepository, never()).recordPickup(any(), anyShort(), any(), any(), any(), anyShort());
        verify(runStepRepository, never()).completeIfRunning(any(), any(), anyShort(), any(), any(), any());
        verify(runStepRepository, never()).failIfRunning(any(), any(), anyShort(), any(), any(), any());
    }

    @Test
    void anUnknownStepTypeErrorsTheStep() {
        RunStep step = pending(1, "nope");
        givenRun(List.of(step));
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
        givenRun(List.of(step));

        advancer.on(new WorkflowRunStartedEvent(runId));

        verify(runStepRepository, never()).claimPending(any(), any(), any(), any());
    }

    @Test
    void aRejectedInlineTaskLeavesTheClaimedStepWithoutPickupForTheReaper(CapturedOutput output) {
        RunAdvancer saturated = advancer(task -> {
            throw new TaskRejectedException("queue full");
        });
        givenRun(List.of(pending(1, "echo")));
        when(runStepRepository.claimPending(any(), any(), any(), any())).thenReturn(1);

        saturated.on(new WorkflowRunStartedEvent(runId));

        verify(runStepRepository, never()).recordPickup(any(), anyShort(), any(), any(), any(), anyShort());
        verify(runStepRepository, never()).failIfRunning(any(), any(), anyShort(), any(), any(), any());
        assertThat(output).contains("WARN").contains("left for the reaper");
    }

    @Test
    void aProviderStepRunsOnTheProviderPoolAndNotOnTheSharedOne() {
        List<Runnable> shared = new java.util.ArrayList<>();
        List<Runnable> provider = new java.util.ArrayList<>();
        RunAdvancer split = advancer(shared::add, provider::add);
        givenRun(List.of(pending(1, "slow")));
        when(runStepRepository.claimPending(any(), any(), any(), any())).thenReturn(1);

        split.on(new WorkflowRunStartedEvent(runId));

        assertThat(provider).hasSize(1);
        assertThat(shared).isEmpty();
    }

    @Test
    void aStepOnTheSharedPoolNeverReachesTheProviderPool() {
        List<Runnable> shared = new java.util.ArrayList<>();
        List<Runnable> provider = new java.util.ArrayList<>();
        RunAdvancer split = advancer(shared::add, provider::add);
        givenRun(List.of(pending(1, "echo")));
        when(runStepRepository.claimPending(any(), any(), any(), any())).thenReturn(1);

        split.on(new WorkflowRunStartedEvent(runId));

        assertThat(shared).hasSize(1);
        assertThat(provider).isEmpty();
    }

    @Test
    void aProviderStepWaitsForPickupAsLongAsItsExecutorSays() {
        givenRun(List.of(pending(1, "slow")));
        when(runStepRepository.claimPending(any(), any(), any(), any())).thenReturn(1);

        advancer(task -> { }, task -> { }).on(new WorkflowRunStartedEvent(runId));

        verify(runStepRepository).claimPending(any(), eq(NOW), eq(NOW.plus(Duration.ofMinutes(20))), any());
    }

    @Test
    void aFullProviderPoolFailsTheStepAtOnceAsProviderBusyWithoutRunningIt(CapturedOutput output) {
        RunAdvancer saturated = advancer(Runnable::run, task -> {
            throw new TaskRejectedException("queue full");
        });
        RunStep step = pending(1, "slow");
        givenRun(List.of(step));
        when(runStepRepository.claimPending(any(), any(), any(), any())).thenReturn(1);
        when(runStepRepository.failIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(1);

        saturated.on(new WorkflowRunStartedEvent(runId));

        verify(runStepRepository).failIfRunning(eq(step.getId()), eq(runId), anyShort(), eq("provider_busy"),
                contains("Too many"), any());
        verify(runStepRepository, never()).recordPickup(any(), anyShort(), any(), any(), any(), anyShort());
        assertThat(output).contains("WARN").contains("provider_busy");
    }

    @Test
    void aCompletedResultBeforeAnyStartedIsApplied(CapturedOutput output) {
        givenMediaEnabled();
        RunStep step = pending(1, "probe");
        step.markRunning(NOW, NOW.plus(PROBE_QUEUE));
        givenRun(List.of(step));
        givenTiming(step);
        when(runStepRepository.completeIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(1);
        when(runStepRepository.countTopLevelNotCompleted(runId)).thenReturn(0L);
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
        givenRun(List.of(step));
        givenTiming(step);
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
        givenRun(List.of(step));
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

    /** An inline type that records the context it was handed, to read what a fan-out child ran with. */
    private static final class CaptureStepExecutor implements StepExecutor {

        final List<StepContext> contexts = new java.util.ArrayList<>();

        @Override
        public String type() {
            return "capture";
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
            contexts.add(context);
            return "captured";
        }
    }

    private static final String ECHO_ITEMS = "{\"child\":{\"type\":\"echo\"},\"concurrency\":3}";

    private RunStep iterateStep(String config) {
        return new RunStep(UUID.randomUUID(), runId, (short) 1, "iterate", null, config, null);
    }

    private RunStep runningIterateStep(String config, String listInput) {
        RunStep step = new RunStep(UUID.randomUUID(), runId, (short) 1, "iterate", listInput, config, null);
        step.markRunning(NOW);
        return step;
    }

    /** The run's top-level reads answer {@code first}, then each of {@code later}, then the last one again. */
    @SafeVarargs
    private void givenIterateRun(String runInput, List<RunStep> first, List<RunStep>... later) {
        WorkflowRun iterateRun = new WorkflowRun(runId, workflowId, UUID.randomUUID(), runInput, NOW);
        when(workflowRunRepository.findById(runId)).thenReturn(Optional.of(iterateRun));
        when(scheduler.scheduleAtFixedRate(any(), any(Instant.class), any(Duration.class)))
                .thenAnswer(invocation -> mock(ScheduledFuture.class));
        when(runStepRepository.findByRunIdOrderByStepOrderAsc(runId)).thenReturn(first, later);
    }

    private List<RunStep> itemsOf(RunStep parent, String type, String... inputs) {
        List<RunStep> items = new java.util.ArrayList<>();
        for (int index = 0; index < inputs.length; index++) {
            items.add(RunStep.child(runId, parent.getStepOrder(), type, parent.getId(), parent.getAttempt(),
                    (short) index, inputs[index]));
        }
        when(runStepRepository.findChildren(parent.getId(), parent.getAttempt())).thenReturn(items);
        return items;
    }

    private static RunStepRepository.ChildRow row(RunStep child, RunStepStatus status,
            String output, String errorCode) {
        return new RunStepRepository.ChildRow() {
            @Override
            public UUID getId() {
                return child.getId();
            }

            @Override
            public short getItemIndex() {
                return child.getItemIndex();
            }

            @Override
            public RunStepStatus getStatus() {
                return status;
            }

            @Override
            public String getOutput() {
                return output;
            }

            @Override
            public String getOutputs() {
                return null;
            }

            @Override
            public String getErrorCode() {
                return errorCode;
            }

            @Override
            public String getErrorMessage() {
                return errorCode == null ? null : "child failed";
            }
        };
    }

    private static List<RunStepRepository.ChildRow> rows(List<RunStep> items, RunStepStatus status) {
        return items.stream()
                .map(item -> row(item, status, status == RunStepStatus.COMPLETED ? item.getInput() : null, null))
                .toList();
    }

    @Test
    void fanoutStepExecutesInTheClaimAndRecordsItsDeadline() {
        RunStep step = iterateStep(ECHO_ITEMS);
        givenIterateRun("[1,2,3,4,5,6,7]", List.of(step));
        List<RunStep> items = itemsOf(step, "echo", "1", "2", "3", "4", "5", "6", "7");
        when(runStepRepository.claimPending(any(), any(), any(), any())).thenReturn(1);
        when(runStepRepository.recordPickup(any(), anyShort(), any(), any(), any(), anyShort())).thenReturn(1);
        when(runStepRepository.findChildStates(step.getId(), (short) 1))
                .thenReturn(rows(items, RunStepStatus.PENDING));
        Instant deadline = NOW.plusSeconds(540);

        advancer.on(new WorkflowRunStartedEvent(runId));

        verify(runStepRepository).saveAll(any());
        verify(runStepRepository).recordPickup(step.getId(), (short) 1, NOW, deadline.plus(LEASE), deadline,
                (short) 0);
        verify(scheduler, never()).scheduleAtFixedRate(any(), any(Instant.class), any(Duration.class));
    }

    @Test
    void emptyIterateDeadlineIsTheLease() {
        RunStep step = iterateStep(ECHO_ITEMS);
        RunStep done = completed(1, step.getId(), "{}");
        givenIterateRun("[]", List.of(step), List.of(done));
        when(runStepRepository.claimPending(any(), any(), any(), any())).thenReturn(1);
        when(runStepRepository.recordPickup(any(), anyShort(), any(), any(), any(), anyShort())).thenReturn(1);
        when(runStepRepository.completeIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(1);
        when(runStepRepository.countTopLevelNotCompleted(runId)).thenReturn(0L);

        advancer.on(new WorkflowRunStartedEvent(runId));

        verify(runStepRepository).recordPickup(step.getId(), (short) 1, NOW, NOW.plus(LEASE).plus(LEASE),
                NOW.plus(LEASE), (short) 0);
        verify(runStepRepository).completeIfRunning(step.getId(), runId, (short) 1,
                "{\"results\":[],\"texts\":[],\"succeeded\":0,\"failed\":0,\"total\":0,\"dropped\":0}", null, NOW);
    }

    @Test
    void runningIterateIsProgressedInsteadOfWaiting() {
        RunStep step = runningIterateStep("{\"child\":{\"type\":\"echo\"},\"concurrency\":1}", "[\"a\",\"b\"]");
        givenIterateRun("[\"a\",\"b\"]", List.of(step));
        List<RunStep> items = itemsOf(step, "echo", "a", "b");
        when(runStepRepository.findChildStates(step.getId(), (short) 1)).thenReturn(
                rows(items, RunStepStatus.PENDING),
                List.of(row(items.get(0), RunStepStatus.RUNNING, null, null),
                        row(items.get(1), RunStepStatus.PENDING, null, null)));
        when(runStepRepository.claimPendingChild(any(), any(), anyShort(), anyInt(), any(),
                any(), any())).thenReturn(1);

        advancer(task -> { }, task -> { }).advance(runId);

        verify(runStepRepository).claimPendingChild(items.get(0).getId(), step.getId(), (short) 1, 1, NOW,
                NOW.plus(LEASE), "a");
        verify(runStepRepository, never()).claimPendingChild(eq(items.get(1).getId()), any(), anyShort(),
                anyInt(), any(), any(), any());
        verify(runStepRepository, never()).claimPending(any(), any(), any(), any());
    }

    @Test
    void childDispatchUsesTheStoredItemAndRenderedConfig() {
        String config = "{\"child\":{\"type\":\"capture\",\"config\":{\"lang\":\"vi\",\"item\":\"{item}\","
                + "\"label\":\"Cảnh {index}: {item.title}\",\"n\":\"{index}\"}},\"concurrency\":1}";
        RunStep step = runningIterateStep(config, "[{\"title\":\"A\"},{\"title\":\"Mở đầu\"}]");
        givenIterateRun("x", List.of(step), List.of(completed(1, step.getId(), "{}")));
        List<RunStep> items = itemsOf(step, "capture", "{\"title\":\"A\"}", "{\"title\":\"Mở đầu\"}");
        when(runStepRepository.findChildStates(step.getId(), (short) 1)).thenReturn(
                List.of(row(items.get(0), RunStepStatus.COMPLETED, "x", null),
                        row(items.get(1), RunStepStatus.PENDING, null, null)),
                List.of(row(items.get(0), RunStepStatus.COMPLETED, "x", null),
                        row(items.get(1), RunStepStatus.COMPLETED, "captured", null)));
        when(runStepRepository.claimPendingChild(any(), any(), anyShort(), anyInt(), any(),
                any(), any())).thenReturn(1);
        when(runStepRepository.recordPickup(any(), anyShort(), any(), any(), any(), anyShort())).thenReturn(1);
        when(runStepRepository.completeIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(1);

        advancer.advance(runId);

        assertThat(capture.contexts).hasSize(1);
        StepContext context = capture.contexts.get(0);
        assertThat(context.input()).isEqualTo("{\"title\":\"Mở đầu\"}");
        assertThat(context.itemIndex()).isEqualTo(1);
        assertThat(context.stepId()).isEqualTo(items.get(1).getId());
        assertThat(context.config()).isEqualTo("{\"lang\":\"vi\",\"item\":{\"title\":\"Mở đầu\"},"
                + "\"label\":\"Cảnh 1: Mở đầu\",\"n\":1}");
    }

    @Test
    void aChildWhoseConfigNamesAMissingFieldFailsInTheClaimWithoutRunning() {
        String config = "{\"child\":{\"type\":\"capture\",\"config\":{\"text\":\"{item.caption}\"}},"
                + "\"concurrency\":1}";
        RunStep step = runningIterateStep(config, "[{\"title\":\"A\"}]");
        givenIterateRun("x", List.of(step));
        List<RunStep> items = itemsOf(step, "capture", "{\"title\":\"A\"}");
        when(runStepRepository.findChildStates(step.getId(), (short) 1)).thenReturn(
                rows(items, RunStepStatus.PENDING),
                List.of(row(items.get(0), RunStepStatus.ERROR, null,
                        "iterate_item_field_missing")));
        when(runStepRepository.claimPendingChild(any(), any(), anyShort(), anyInt(), any(),
                any(), any())).thenReturn(1);
        when(runStepRepository.failIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(1);

        advancer.advance(runId);

        verify(runStepRepository).failIfRunning(eq(items.get(0).getId()), eq(runId), eq((short) 1),
                eq("iterate_item_field_missing"), any(), eq(NOW));
        assertThat(capture.contexts).isEmpty();
    }

    @Test
    void childClaimFailedAtOnceFailsTheParentInTheSameAdvance() {
        RunStep step = runningIterateStep("{\"child\":{\"type\":\"echo\"},\"concurrency\":1}", "[\"a\",\"b\"]");
        givenIterateRun("x", List.of(step));
        List<RunStep> items = itemsOf(step, "echo", "a", "b");
        when(runStepRepository.findChildStates(step.getId(), (short) 1)).thenReturn(
                rows(items, RunStepStatus.PENDING),
                List.of(row(items.get(0), RunStepStatus.ERROR, null,
                        "INSUFFICIENT_CREDITS"),
                        row(items.get(1), RunStepStatus.PENDING, null, null)));
        when(runStepRepository.claimPendingChild(any(), any(), anyShort(), anyInt(), any(),
                any(), any())).thenReturn(1);
        when(credits.enabled()).thenReturn(true);
        when(runStepRepository.findById(items.get(0).getId())).thenReturn(Optional.of(items.get(0)));
        when(credits.debitForStep(any(), any(), any(), anyShort(), any())).thenReturn(false);
        when(runStepRepository.failIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(1);
        RunStepRepository.Lineage parentLineage = lineageOf("iterate", null);
        RunStepRepository.Lineage itemLineage = lineageOf("echo", step.getId());
        when(runStepRepository.findLineageById(step.getId())).thenReturn(Optional.of(parentLineage));
        when(runStepRepository.findLineageById(items.get(0).getId())).thenReturn(Optional.of(itemLineage));

        advancer.advance(runId);

        verify(runStepRepository).failIfRunning(items.get(0).getId(), runId, (short) 1, "INSUFFICIENT_CREDITS",
                RunStepTransactions.NOT_ENOUGH_CREDITS, NOW);
        verify(runStepRepository).failIfRunning(step.getId(), runId, (short) 1, "INSUFFICIENT_CREDITS",
                RunStepTransactions.NOT_ENOUGH_CREDITS, NOW);
        verify(runStepRepository).skipPendingChildren(step.getId());
        verify(runStepRepository, org.mockito.Mockito.times(1)).claimPendingChild(any(), any(), anyShort(),
                anyInt(), any(), any(), any());
    }

    private RunStepRepository.Lineage lineageOf(String type, UUID parentStepId) {
        RunStepRepository.Lineage lineage = mock(RunStepRepository.Lineage.class);
        when(lineage.getType()).thenReturn(type);
        when(lineage.getParentStepId()).thenReturn(parentStepId);
        when(lineage.getParentAttempt()).thenReturn(parentStepId == null ? null : (short) 1);
        return lineage;
    }

    @Test
    void aPassWhoseClaimsAreAllLostEndsTheDecisionLoop() {
        RunStep step = runningIterateStep("{\"child\":{\"type\":\"echo\"},\"concurrency\":2}", "[1,2,3,4,5]");
        givenIterateRun("x", List.of(step));
        List<RunStep> items = itemsOf(step, "echo", "1", "2", "3", "4", "5");
        when(runStepRepository.findChildStates(step.getId(), (short) 1))
                .thenReturn(rows(items, RunStepStatus.PENDING));

        advancer.advance(runId);

        verify(runStepRepository, org.mockito.Mockito.times(1)).findChildStates(step.getId(), (short) 1);
        verify(runStepRepository, org.mockito.Mockito.times(2)).claimPendingChild(any(), eq(step.getId()),
                eq((short) 1), eq(2), eq(NOW), eq(NOW.plus(LEASE)), any());
    }

    @Test
    void aPassThatOnlyStartsItemsEndsTheDecisionLoop() {
        RunStep step = runningIterateStep("{\"child\":{\"type\":\"echo\"},\"concurrency\":2}", "[1,2,3,4,5]");
        givenIterateRun("x", List.of(step));
        List<RunStep> items = itemsOf(step, "echo", "1", "2", "3", "4", "5");
        when(runStepRepository.findChildStates(step.getId(), (short) 1))
                .thenReturn(rows(items, RunStepStatus.PENDING));
        when(runStepRepository.claimPendingChild(any(), any(), anyShort(), anyInt(), any(), any(), any()))
                .thenReturn(1);

        advancer(task -> { }, task -> { }).advance(runId);

        verify(runStepRepository, org.mockito.Mockito.times(1)).findChildStates(step.getId(), (short) 1);
        verify(runStepRepository, org.mockito.Mockito.times(2)).claimPendingChild(any(), eq(step.getId()),
                eq((short) 1), eq(2), eq(NOW), eq(NOW.plus(LEASE)), any());
    }

    private final Map<UUID, RunStepStatus> itemStatuses = new java.util.HashMap<>();

    /** The items' statuses follow the claims and failures the advancer makes, as the database would. */
    private void givenItemsFollowTheirTransitions(RunStep step, List<RunStep> items) {
        items.forEach(item -> itemStatuses.put(item.getId(), RunStepStatus.PENDING));
        when(runStepRepository.findChildStates(step.getId(), (short) 1)).thenAnswer(invocation -> items.stream()
                .map(item -> row(item, itemStatuses.get(item.getId()), null,
                        itemStatuses.get(item.getId()) == RunStepStatus.ERROR ? "provider_busy" : null))
                .toList());
        when(runStepRepository.claimPendingChild(any(), any(), anyShort(), anyInt(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    itemStatuses.put(invocation.getArgument(0), RunStepStatus.RUNNING);
                    return 1;
                });
        when(runStepRepository.failIfRunning(any(), any(), anyShort(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    itemStatuses.computeIfPresent(invocation.getArgument(0), (id, status) -> RunStepStatus.ERROR);
                    return 1;
                });
        RunStepRepository.Lineage itemLineage = lineageOf("slow", step.getId());
        items.forEach(item -> when(runStepRepository.findLineageById(item.getId()))
                .thenReturn(Optional.of(itemLineage)));
    }

    @Test
    void anItemTheProviderPoolRefusesDoesNotLeaveTheRunIdle() {
        RunStep step = runningIterateStep("{\"child\":{\"type\":\"slow\"},\"concurrency\":1}", "[1,2,3]");
        givenIterateRun("x", List.of(step), List.of(completed(1, step.getId(), "{}")));
        List<RunStep> items = itemsOf(step, "slow", "1", "2", "3");
        givenItemsFollowTheirTransitions(step, items);
        when(runStepRepository.completeIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(1, 0);

        advancer(Runnable::run, task -> {
            throw new TaskRejectedException("queue full");
        }).advance(runId);

        verify(runStepRepository, org.mockito.Mockito.times(3)).claimPendingChild(any(), eq(step.getId()),
                eq((short) 1), eq(1), any(), any(), any());
        items.forEach(item -> verify(runStepRepository).failIfRunning(eq(item.getId()), eq(runId), anyShort(),
                eq("provider_busy"), contains("Too many"), any()));
        verify(runStepRepository).completeIfRunning(step.getId(), runId, (short) 1,
                "{\"results\":[null,null,null],\"texts\":[],\"succeeded\":0,\"failed\":3,\"total\":3,\"dropped\":0}",
                null, NOW);
    }

    @Test
    void anItemTheProviderPoolRefusesFailsAFailFastStepInTheSamePass() {
        RunStep step = runningIterateStep(
                "{\"child\":{\"type\":\"slow\"},\"concurrency\":3,\"onItemError\":\"fail\"}", "[1,2,3]");
        givenIterateRun("x", List.of(step));
        List<RunStep> items = itemsOf(step, "slow", "1", "2", "3");
        givenItemsFollowTheirTransitions(step, items);

        advancer(Runnable::run, task -> {
            throw new TaskRejectedException("queue full");
        }).advance(runId);

        verify(runStepRepository).failIfRunning(eq(step.getId()), eq(runId), eq((short) 1),
                eq("iterate_item_failed"), contains("Item 0 failed"), any());
        verify(runStepRepository, never()).completeIfRunning(any(), any(), anyShort(), any(), any(), any());
    }

    @Test
    void itemsThatAllFailInTheirClaimCompleteTheStepInOneAdvance() {
        String config = "{\"child\":{\"type\":\"capture\",\"config\":{\"t\":\"{item.title}\"}},\"concurrency\":1}";
        String list = "[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\"]";
        RunStep step = runningIterateStep(config, list);
        givenIterateRun("x", List.of(step), List.of(completed(1, step.getId(), "{}")));
        List<RunStep> items = itemsOf(step, "capture", "a", "b", "c", "d", "e", "f");
        Map<UUID, RunStepStatus> statuses = new java.util.HashMap<>();
        items.forEach(item -> statuses.put(item.getId(), RunStepStatus.PENDING));
        when(runStepRepository.findChildStates(step.getId(), (short) 1)).thenAnswer(invocation -> items.stream()
                .map(item -> row(item, statuses.get(item.getId()), null,
                        statuses.get(item.getId()) == RunStepStatus.ERROR ? "iterate_item_field_missing" : null))
                .toList());
        when(runStepRepository.claimPendingChild(any(), any(), anyShort(), anyInt(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    statuses.put(invocation.getArgument(0), RunStepStatus.RUNNING);
                    return 1;
                });
        when(runStepRepository.failIfRunning(any(), any(), anyShort(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    statuses.put(invocation.getArgument(0), RunStepStatus.ERROR);
                    return 1;
                });
        when(runStepRepository.completeIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(1, 0);
        RunStepRepository.Lineage itemLineage = lineageOf("capture", step.getId());
        items.forEach(item -> when(runStepRepository.findLineageById(item.getId()))
                .thenReturn(Optional.of(itemLineage)));

        advancer.advance(runId);

        verify(runStepRepository, org.mockito.Mockito.times(6)).claimPendingChild(any(), eq(step.getId()),
                eq((short) 1), eq(1), any(), any(), any());
        verify(runStepRepository).completeIfRunning(step.getId(), runId, (short) 1,
                "{\"results\":[null,null,null,null,null,null],\"texts\":[],\"succeeded\":0,\"failed\":6,"
                        + "\"total\":6,\"dropped\":0}", null, NOW);
        assertThat(capture.contexts).isEmpty();
    }

    @Test
    void aStringItemThatReadsAsANumberRendersAsTextInTheFirstDispatch() {
        RunStep step = iterateStep("{\"child\":{\"type\":\"capture\",\"config\":{\"n\":\"{item}\"}},"
                + "\"concurrency\":1}");
        givenIterateRun("[\"42\",\"7\"]", List.of(step), List.of(completed(1, step.getId(), "{}")));
        List<RunStep> items = itemsOf(step, "capture", "42", "7");
        when(runStepRepository.recordPickup(any(), anyShort(), any(), any(), any(), anyShort())).thenReturn(1);
        when(runStepRepository.claimPending(any(), any(), any(), any())).thenReturn(1);
        when(runStepRepository.recordPickup(any(), anyShort(), any(), any(), any(), anyShort())).thenReturn(1);
        when(runStepRepository.findChildStates(step.getId(), (short) 1))
                .thenReturn(rows(items, RunStepStatus.PENDING));
        when(runStepRepository.claimPendingChild(any(), any(), anyShort(), anyInt(), any(), any(), any()))
                .thenReturn(1);

        advancer.on(new WorkflowRunStartedEvent(runId));

        assertThat(step.getInput()).isNull();
        assertThat(capture.contexts).extracting(StepContext::config).containsExactly("{\"n\":\"42\"}");
    }

    @Test
    void aStringItemThatReadsAsANumberRendersAsTextInALaterAdvanceToo() {
        RunStep step = runningIterateStep("{\"child\":{\"type\":\"capture\",\"config\":{\"n\":\"{item}\"}},"
                + "\"concurrency\":1}", "[\"42\",\"7\"]");
        givenIterateRun("[\"42\",\"7\"]", List.of(step), List.of(completed(1, step.getId(), "{}")));
        List<RunStep> items = itemsOf(step, "capture", "42", "7");
        when(runStepRepository.recordPickup(any(), anyShort(), any(), any(), any(), anyShort())).thenReturn(1);
        when(runStepRepository.findChildStates(step.getId(), (short) 1))
                .thenReturn(rows(items, RunStepStatus.PENDING));
        when(runStepRepository.claimPendingChild(any(), any(), anyShort(), anyInt(), any(), any(), any()))
                .thenReturn(1);

        advancer.advance(runId);

        assertThat(capture.contexts).extracting(StepContext::config).containsExactly("{\"n\":\"42\"}");
    }

    @Test
    void providerPoolChildIsLimitedToProviderConcurrency() {
        RunStep step = runningIterateStep("{\"child\":{\"type\":\"slow\"},\"concurrency\":10}", "[1,2,3,4]");
        givenIterateRun("x", List.of(step));
        List<RunStep> items = itemsOf(step, "slow", "1", "2", "3", "4");
        when(runStepRepository.findChildStates(step.getId(), (short) 1))
                .thenReturn(rows(items, RunStepStatus.PENDING));

        advancer.advance(runId);

        verify(runStepRepository, org.mockito.Mockito.times(1)).claimPendingChild(any(), eq(step.getId()),
                eq((short) 1), eq(1), any(), eq(NOW.plus(Duration.ofMinutes(20))), any());
        verify(runStepRepository, never()).claimPendingChild(any(), any(), anyShort(), eq(10), any(), any(), any());
    }

    @Test
    void aProviderConcurrencyOutsideThePoolIsRefusedAtConstruction() {
        assertThatCode(() -> providerAdvancer(0, 4)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("provider-concurrency");
        assertThatCode(() -> providerAdvancer(5, 4)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1 to 4");
        assertThatCode(() -> providerAdvancer(4, 4)).doesNotThrowAnyException();
    }

    private RunAdvancer providerAdvancer(int providerConcurrency, int poolSize) {
        return new RunAdvancer(workflowRunRepository, runStepRepository, transactions, new InputResolver(), registry,
                Runnable::run, Runnable::run, scheduler, Tracer.NOOP, ObservationRegistry.NOOP, meters, LEASE, clock,
                checkpoints, null, validator, null, providerConcurrency, poolSize);
    }

    @Test
    void aWiderProviderConcurrencyLetsThatManyProviderChildrenRunAtOnce() {
        RunStep step = runningIterateStep("{\"child\":{\"type\":\"slow\"},\"concurrency\":10}", "[1,2,3,4]");
        givenIterateRun("x", List.of(step));
        List<RunStep> items = itemsOf(step, "slow", "1", "2", "3", "4");
        when(runStepRepository.findChildStates(step.getId(), (short) 1))
                .thenReturn(rows(items, RunStepStatus.PENDING));

        providerAdvancer(3, 4).advance(runId);

        verify(runStepRepository, org.mockito.Mockito.times(3)).claimPendingChild(any(), eq(step.getId()),
                eq((short) 1), eq(3), any(), any(), any());
    }

    @Test
    void completeLoopsToTheNextStepWithoutANestedAdvance() {
        RunStep step = runningIterateStep("{\"child\":{\"type\":\"echo\"},\"concurrency\":1}", "[\"a\"]");
        RunStep next = pending(2, "echo");
        RunStep stepDone = completed(1, step.getId(), "{}");
        givenIterateRun("[\"a\"]", List.of(step, next));
        List<RunStep> items = itemsOf(step, "echo", "a");
        when(runStepRepository.findChildStates(step.getId(), (short) 1))
                .thenReturn(rows(items, RunStepStatus.COMPLETED));
        when(runStepRepository.completeIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(1);
        when(runStepRepository.countTopLevelNotCompleted(runId)).thenReturn(1L);
        when(runStepRepository.claimPending(any(), any(), any(), any())).thenReturn(1);
        List<Integer> advanceFrames = new java.util.ArrayList<>();
        when(runStepRepository.findByRunIdOrderByStepOrderAsc(runId)).thenAnswer(invocation -> {
            advanceFrames.add((int) java.util.Arrays.stream(Thread.currentThread().getStackTrace())
                    .filter(frame -> frame.getClassName().equals(RunAdvancer.class.getName())
                            && frame.getMethodName().equals("advance"))
                    .count());
            return advanceFrames.size() == 1 ? List.of(step, next) : List.of(stepDone, next);
        });

        advancer(task -> { }, task -> { }).on(new WorkflowRunStartedEvent(runId));

        verify(runStepRepository).claimPending(eq(next.getId()), any(), any(), any());
        assertThat(advanceFrames).hasSize(2).containsOnly(1);
    }

    @Test
    void childFailureAdvancesTheRun() {
        RunStep step = runningIterateStep("{\"child\":{\"type\":\"fail\"},\"concurrency\":1}", "[\"a\"]");
        givenIterateRun("[\"a\"]", List.of(step), List.of(step), List.of(completed(1, step.getId(), "{}")));
        List<RunStep> items = itemsOf(step, "fail", "a");
        when(runStepRepository.findChildStates(step.getId(), (short) 1)).thenReturn(
                rows(items, RunStepStatus.PENDING),
                List.of(row(items.get(0), RunStepStatus.ERROR, null, "boom")));
        when(runStepRepository.claimPendingChild(any(), any(), anyShort(), anyInt(), any(),
                any(), any())).thenReturn(1);
        when(runStepRepository.recordPickup(any(), anyShort(), any(), any(), any(), anyShort())).thenReturn(1);
        when(runStepRepository.failIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(1);
        when(runStepRepository.completeIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(1, 0);
        RunStepRepository.Lineage itemLineage = lineageOf("fail", step.getId());
        when(runStepRepository.findLineageById(items.get(0).getId())).thenReturn(Optional.of(itemLineage));

        advancer.advance(runId);

        verify(runStepRepository, atLeastOnce()).completeIfRunning(step.getId(), runId, (short) 1,
                "{\"results\":[null],\"texts\":[],\"succeeded\":0,\"failed\":1,\"total\":1,\"dropped\":0}", null,
                NOW);
        verify(runStepRepository, never()).failIfRunning(eq(step.getId()), any(), anyShort(), any(), any(), any());
    }

    @Test
    void topLevelFailureStillHalts() {
        RunStep step1 = pending(1, "fail");
        givenRun(List.of(step1, pending(2, "echo")));
        when(runStepRepository.claimPending(any(), any(), any(), any())).thenReturn(1);
        when(runStepRepository.recordPickup(any(), anyShort(), any(), any(), any(), anyShort())).thenReturn(1);
        when(runStepRepository.failIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(1);
        when(workflowRunRepository.findById(runId)).thenReturn(Optional.of(run));

        advancer.on(new WorkflowRunStartedEvent(runId));

        verify(runStepRepository, org.mockito.Mockito.times(1)).findByRunIdOrderByStepOrderAsc(runId);
        verify(runStepRepository, org.mockito.Mockito.times(1)).claimPending(any(), any(), any(), any());
    }

    @Test
    void regeneratedChildIsDispatchedThroughTheRegeneratedClaim() {
        RunStep step = runningIterateStep("{\"child\":{\"type\":\"echo\"},\"concurrency\":2}", "[\"a\",\"b\"]");
        List<RunStep> items = itemsOf(step, "echo", "a", "b");
        RunStep child = items.get(1);
        when(workflowRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(runStepRepository.findById(child.getId())).thenReturn(Optional.of(child));
        when(runStepRepository.findById(step.getId())).thenReturn(Optional.of(step));
        when(runStepRepository.claimRegeneratedChild(any(), any(), anyShort(), anyInt(),
                any(), any(), any())).thenReturn(1);

        advancer(task -> { }, task -> { }).onChildRegenerated(new IterateChildRegenerated(runId, child.getId()));

        verify(runStepRepository).claimRegeneratedChild(child.getId(), step.getId(), (short) 1, 2, NOW,
                NOW.plus(LEASE), "b");
        verify(runStepRepository, never()).claimPendingChild(any(), any(), anyShort(),
                anyInt(), any(), any(), any());
    }

    private void assertProviderDataRejected(String capability, Map<String, Object> data, String field) {
        RunStep step = pending(1, capability);
        step.markRunning(NOW, NOW.plus(PROBE_QUEUE));
        givenRun(List.of(step));
        givenTiming(step);
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
                "durationMs", 9_000,
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
