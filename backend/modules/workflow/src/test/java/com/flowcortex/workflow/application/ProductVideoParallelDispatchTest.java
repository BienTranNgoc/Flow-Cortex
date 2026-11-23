package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flowcortex.credit.api.CreditAccounts;
import com.flowcortex.media.api.MediaCapability;
import com.flowcortex.media.api.MediaControlResponse;
import com.flowcortex.media.api.MediaError;
import com.flowcortex.media.api.MediaJob;
import com.flowcortex.media.api.MediaJobCompleted;
import com.flowcortex.media.api.MediaJobInput;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.media.api.MediaLimits;
import com.flowcortex.media.api.MediaOutput;
import com.flowcortex.media.api.MediaResult;
import com.flowcortex.media.api.provider.ProviderJob;
import com.flowcortex.workflow.api.ProductVideoWorkflow;
import com.flowcortex.workflow.domain.RunStatus;
import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.domain.RunStepStatus;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;

class ProductVideoParallelDispatchTest {

    private static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");
    private static final UUID REVISION_ID = UUID.fromString("a71ea3ba-d72a-4c2c-985c-3a7fd98853c4");
    private static final Duration LEASE = Duration.ofSeconds(120);

    private final ReentrantLock dbLock = new ReentrantLock();
    private final WorkflowRunRepository runRepo = mock(WorkflowRunRepository.class);
    private final RunStepRepository stepRepo = mock(RunStepRepository.class);
    private final CreditAccounts creditAccounts = mock(CreditAccounts.class);
    private final RunUpdates runUpdates = mock(RunUpdates.class);
    private final ChildRuns childRuns = mock(ChildRuns.class);
    private final MediaJobs mediaJobs = mock(MediaJobs.class);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private final UUID runId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();
    private final List<ProviderJob> dispatchedProviderJobs = new CopyOnWriteArrayList<>();
    private final List<MediaJob> dispatchedMediaJobs = new CopyOnWriteArrayList<>();
    private final List<String> creditEvents = new CopyOnWriteArrayList<>();
    private final Map<UUID, RunStep> stepsById = new LinkedHashMap<>();

    private WorkflowRun run;
    private List<RunStep> orderedSteps;
    private RunAdvancer advancer;
    private RunStepTransactions transactions;
    private WorkflowJobControl jobControl;
    private RunCancellation cancellation;

    @BeforeEach
    void setUp() {
        dispatchedProviderJobs.clear();
        dispatchedMediaJobs.clear();
        creditEvents.clear();
        stepsById.clear();
        run = new WorkflowRun(runId, ProductVideoWorkflow.ID, ownerId, "{}", NOW);
        String refJson = "[{\"key\":\"image_1\",\"bucket\":\"flowcortex-media\",\"objectKey\":\"runs/"
                + runId + "/inputs/image_1\"}]";
        String clipConfig1 = "{\"profile\":\"vertical_ad_clip_v1\",\"payloadFields\":{\"prompt\":\"scene1Prompt\","
                + "\"sourceRevisionId\":\"sourceRevisionId\"},\"referenceImage\":\"image_1\"}";
        String clipConfig2 = "{\"profile\":\"vertical_ad_clip_v1\",\"payloadFields\":{\"prompt\":\"scene2Prompt\","
                + "\"sourceRevisionId\":\"sourceRevisionId\"},\"referenceImage\":\"image_1\"}";
        String renderConfig = "{\"profile\":\"vertical_ad_v1\",\"outputInputs\":["
                + "{\"stepOrder\":4,\"outputName\":\"speech.wav\",\"inputKey\":\"speech\"},"
                + "{\"stepOrder\":5,\"outputName\":\"clip.mp4\",\"inputKey\":\"generated_video_1\"},"
                + "{\"stepOrder\":6,\"outputName\":\"clip.mp4\",\"inputKey\":\"generated_video_2\"}"
                + "]}";

        RunStep s1 = completedStep(1, "generate_script", "{\"script\":\"hi\"}", null);
        RunStep s2 = completedStep(2, "APPROVAL_CHECKPOINT", "{\"script\":\"hi\"}", null);
        RunStep s3 = completedStep(3, "generate_storyboard", "{\"profile\":\"product_ad_storyboard_v1\","
                + "\"sourceRevisionId\":\"" + REVISION_ID + "\",\"scene1Prompt\":\"Scene 1\","
                + "\"scene2Prompt\":\"Scene 2\"}", null);
        RunStep s4 = completedStep(4, "generate_speech", "{\"profile\":\"vi_voice_v1\",\"durationMs\":4000,"
                + "\"provider\":\"fixture\",\"voice\":\"silence\",\"speed\":\"+0%\","
                + "\"sourceRevisionId\":\"" + REVISION_ID + "\",\"idempotencyKey\":\"" + "a".repeat(64)
                + "\",\"format\":\"wav\"}",
                "[{\"name\":\"speech.wav\",\"bucket\":\"flowcortex-media\",\"objectKey\":\"runs/"
                        + runId + "/steps/4/attempts/1/speech.wav\",\"mime\":\"audio/wav\",\"bytes\":100}]");
        RunStep s5 = new RunStep(UUID.randomUUID(), runId, (short) 5, "generate_video", refJson, clipConfig1, "{step:3}");
        RunStep s6 = new RunStep(UUID.randomUUID(), runId, (short) 6, "generate_video", refJson, clipConfig2, "{step:3}");
        RunStep s7 = new RunStep(UUID.randomUUID(), runId, (short) 7, "render_video", refJson, renderConfig, null);

        orderedSteps = List.of(s1, s2, s3, s4, s5, s6, s7);
        orderedSteps.forEach(step -> stepsById.put(step.getId(), step));

        when(mediaJobs.isEnabled()).thenReturn(true);
        when(mediaJobs.providerLimits("generate_video"))
                .thenReturn(new MediaLimits(Duration.ofMinutes(15), Duration.ofMinutes(10), 10_000_000L));
        when(mediaJobs.limitsFor("render_video"))
                .thenReturn(new MediaLimits(Duration.ofMinutes(30), Duration.ofMinutes(5), 500_000_000L));
        doAnswer(inv -> {
            dispatchedProviderJobs.add(inv.getArgument(0));
            return null;
        }).when(mediaJobs).enqueueProvider(any());
        doAnswer(inv -> {
            dispatchedMediaJobs.add(inv.getArgument(0));
            return null;
        }).when(mediaJobs).enqueue(any());

        when(creditAccounts.enabled()).thenReturn(true);
        when(creditAccounts.debitForStep(any(), any(), any(), anyShort(), any())).thenAnswer(inv -> {
            creditEvents.add("debit:" + inv.getArgument(2));
            return true;
        });
        doAnswer(inv -> {
            creditEvents.add("refund:" + inv.getArgument(1) + ":" + inv.getArgument(3));
            return null;
        }).when(creditAccounts).refundStep(any(), any(), anyShort(), any());

        wireStatefulRepos();

        StepExecutorRegistry registry = new StepExecutorRegistry(List.of(
                new ProviderStepExecutor("generate_script", "product_ad_v1", mediaJobs,
                        Clock.fixed(NOW, ZoneOffset.UTC)),
                new ProviderStepExecutor("generate_storyboard", "product_ad_storyboard_v1", mediaJobs,
                        Clock.fixed(NOW, ZoneOffset.UTC)),
                new ProviderStepExecutor("generate_speech", "vi_voice_v1", mediaJobs,
                        Clock.fixed(NOW, ZoneOffset.UTC)),
                new ProviderStepExecutor("generate_video", "vertical_ad_clip_v1", mediaJobs,
                        Clock.fixed(NOW, ZoneOffset.UTC)),
                new RemoteStepExecutor(MediaCapability.RENDER_VIDEO.value(), mediaJobs,
                        Clock.fixed(NOW, ZoneOffset.UTC))));
        transactions = new RunStepTransactions(stepRepo, runRepo, creditAccounts, runUpdates,
                mock(ApplicationEventPublisher.class), childRuns);
        advancer = new RunAdvancer(runRepo, stepRepo, transactions, new InputResolver(), registry,
                Runnable::run, Runnable::run, scheduler, Tracer.NOOP, ObservationRegistry.NOOP,
                new SimpleMeterRegistry(), LEASE, Clock.fixed(NOW, ZoneOffset.UTC),
                mock(ReviewCheckpointTransactions.class), mediaJobs, validator, null, true);
        jobControl = new WorkflowJobControl(runRepo, stepRepo);
        cancellation = new RunCancellation(runRepo, stepRepo, registry, mediaJobs,
                Clock.fixed(NOW, ZoneOffset.UTC), runUpdates, childRuns, transactions, true);
    }

    private void wireStatefulRepos() {
        when(runRepo.findById(runId)).thenAnswer(inv -> withLock(() -> Optional.of(run)));
        when(runRepo.findByIdAndOwnerId(runId, ownerId)).thenAnswer(inv -> withLock(() -> Optional.of(run)));
        when(runRepo.lockById(runId)).thenReturn(List.of(runId));
        when(runRepo.save(any(WorkflowRun.class))).thenAnswer(inv -> {
            run = inv.getArgument(0);
            return run;
        });
        when(runRepo.requestCancel(runId, ownerId)).thenAnswer(inv -> withLock(() -> {
            if (run.getStatus() == RunStatus.RUNNING || run.getStatus() == RunStatus.WAITING_APPROVAL) {
                setRunField("status", RunStatus.CANCELLING);
                return 1;
            }
            return 0;
        }));
        when(runRepo.settleCancelled(eq(runId), any())).thenAnswer(inv -> withLock(() -> {
            if (run.getStatus() == RunStatus.CANCELLING
                    && orderedSteps.stream().noneMatch(s -> s.getStatus() == RunStepStatus.RUNNING)) {
                setRunField("status", RunStatus.CANCELLED);
                setRunField("finishedAt", inv.getArgument(1));
                return 1;
            }
            return 0;
        }));

        when(stepRepo.findByRunIdOrderByStepOrderAsc(runId)).thenAnswer(inv -> withLock(() -> List.copyOf(orderedSteps)));
        when(stepRepo.findById(any())).thenAnswer(inv -> withLock(() -> Optional.ofNullable(stepsById.get(inv.getArgument(0)))));
        when(stepRepo.findTimingById(any())).thenAnswer(inv -> withLock(() -> {
            RunStep step = stepsById.get(inv.getArgument(0));
            return step == null ? Optional.empty() : Optional.of(timing(step));
        }));
        when(stepRepo.countTopLevelNotCompleted(runId)).thenAnswer(inv -> withLock(() ->
                orderedSteps.stream().filter(s -> s.getStatus() != RunStepStatus.COMPLETED).count()));
        when(stepRepo.claimPending(any(), any(), any(), any())).thenAnswer(inv -> withLock(() -> {
            RunStep step = stepsById.get(inv.getArgument(0));
            if (step == null || step.getStatus() != RunStepStatus.PENDING || run.getStatus() != RunStatus.RUNNING) {
                return 0;
            }
            step.markRunning(inv.getArgument(1), inv.getArgument(2));
            setStepField(step, "input", inv.getArgument(3));
            return 1;
        }));
        when(stepRepo.skipPending(runId)).thenAnswer(inv -> withLock(() -> {
            int skipped = 0;
            for (RunStep step : orderedSteps) {
                if (step.getStatus() == RunStepStatus.PENDING) {
                    setStepField(step, "status", RunStepStatus.SKIPPED);
                    skipped++;
                }
            }
            return skipped;
        }));
        when(stepRepo.completeIfRunning(any(), eq(runId), anyShort(), any(), any(), any())).thenAnswer(inv -> withLock(() -> {
            RunStep step = stepsById.get(inv.getArgument(0));
            short attempt = inv.getArgument(2);
            if (step == null || step.getStatus() != RunStepStatus.RUNNING || step.getAttempt() != attempt) {
                return 0;
            }
            step.markCompleted(step.getInput(), inv.getArgument(3), inv.getArgument(5), inv.getArgument(4));
            return 1;
        }));
        when(stepRepo.failIfRunning(any(), eq(runId), anyShort(), any(), any(), any())).thenAnswer(inv -> withLock(() -> {
            RunStep step = stepsById.get(inv.getArgument(0));
            short attempt = inv.getArgument(2);
            if (step == null || step.getStatus() != RunStepStatus.RUNNING || step.getAttempt() != attempt) {
                return 0;
            }
            step.markError(null, inv.getArgument(3), inv.getArgument(4), inv.getArgument(5));
            return 1;
        }));
    }

    private <T> T withLock(java.util.function.Supplier<T> supplier) {
        dbLock.lock();
        try {
            return supplier.get();
        } finally {
            dbLock.unlock();
        }
    }

    private RunStep completedStep(int order, String type, String output, String outputs) {
        RunStep step = new RunStep(UUID.randomUUID(), runId, (short) order, type);
        step.markRunning(NOW);
        step.markCompleted(null, output, NOW);
        if (outputs != null) {
            setStepField(step, "outputs", outputs);
        }
        return step;
    }

    private static RunStepRepository.Timing timing(RunStep step) {
        return new RunStepRepository.Timing() {
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
        };
    }

    private static void setStepField(RunStep target, String fieldName, Object value) {
        try {
            var field = RunStep.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private void setRunField(String fieldName, Object value) {
        try {
            var field = WorkflowRun.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(run, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private MediaResult clipSuccess(RunStep clipStep) {
        String key = "runs/" + runId + "/steps/" + clipStep.getStepOrder() + "/attempts/1/clip.mp4";
        return new MediaResult("1", clipStep.getId(), runId, 1, 0, MediaResult.Status.COMPLETED,
                List.of(new MediaOutput("clip.mp4", "flowcortex-media", key, "video/mp4", 1024L, null)),
                Map.of("profile", "vertical_ad_clip_v1", "durationSeconds", 5, "provider", "wan",
                        "model", "wan-video/wan-2.2-5b-fast", "aspectRatio", "9:16", "resolution", "480p",
                        "sourceRevisionId", REVISION_ID.toString(), "idempotencyKey", "b".repeat(64),
                        "providerTaskId", "pred-" + clipStep.getStepOrder(), "format", "mp4"),
                null, NOW, "video-worker");
    }

    private MediaResult clipError(RunStep clipStep, MediaError.Code code, String message) {
        return new MediaResult("1", clipStep.getId(), runId, 1, 0, MediaResult.Status.ERROR, null, null,
                new MediaError(code, message, false), NOW, "video-worker");
    }

    @Test
    void dispatchesBothClipsBeforeEitherFinishesAndComposesInOrderWhenSixFinishesBeforeFive() {
        advancer.advance(runId);

        assertThat(dispatchedProviderJobs).extracting(ProviderJob::stepOrder).containsExactly(5, 6);
        assertThat(orderedSteps.get(4).getStatus()).isEqualTo(RunStepStatus.RUNNING);
        assertThat(orderedSteps.get(5).getStatus()).isEqualTo(RunStepStatus.RUNNING);
        assertThat(orderedSteps.get(6).getStatus()).isEqualTo(RunStepStatus.PENDING);

        // Clip 6 finishes first: render step must still wait.
        advancer.onRemoteCompleted(new MediaJobCompleted(clipSuccess(orderedSteps.get(5))));
        assertThat(dispatchedMediaJobs).isEmpty();
        assertThat(orderedSteps.get(6).getStatus()).isEqualTo(RunStepStatus.PENDING);

        // Duplicate delivery for clip 6 does nothing.
        advancer.onRemoteCompleted(new MediaJobCompleted(clipSuccess(orderedSteps.get(5))));
        assertThat(dispatchedMediaJobs).isEmpty();

        // Clip 5 finishes second: render step dispatches once with exact input ordering.
        advancer.onRemoteCompleted(new MediaJobCompleted(clipSuccess(orderedSteps.get(4))));
        advancer.onRemoteCompleted(new MediaJobCompleted(clipSuccess(orderedSteps.get(4))));

        assertThat(dispatchedMediaJobs).hasSize(1);
        MediaJob render = dispatchedMediaJobs.getFirst();
        assertThat(render.capability()).isEqualTo(MediaCapability.RENDER_VIDEO);
        assertThat(render.inputs()).extracting(MediaJobInput::key)
                .containsExactly("image_1", "speech", "generated_video_1", "generated_video_2");
        assertThat(render.inputs().get(2).objectKey()).contains("/steps/5/");
        assertThat(render.inputs().get(3).objectKey()).contains("/steps/6/");
    }

    @Test
    void dispatchesBothClipsAndComposesInOrderWhenFiveFinishesBeforeSix() {
        advancer.advance(runId);

        assertThat(dispatchedProviderJobs).extracting(ProviderJob::stepOrder).containsExactly(5, 6);

        advancer.onRemoteCompleted(new MediaJobCompleted(clipSuccess(orderedSteps.get(4))));
        assertThat(dispatchedMediaJobs).isEmpty();

        advancer.onRemoteCompleted(new MediaJobCompleted(clipSuccess(orderedSteps.get(5))));

        assertThat(dispatchedMediaJobs).hasSize(1);
        MediaJob render = dispatchedMediaJobs.getFirst();
        assertThat(render.inputs()).extracting(MediaJobInput::key)
                .containsExactly("image_1", "speech", "generated_video_1", "generated_video_2");
        assertThat(render.inputs().get(2).objectKey()).contains("/steps/5/");
        assertThat(render.inputs().get(3).objectKey()).contains("/steps/6/");
    }

    @Test
    void concurrentAdvancesDispatchEachClipExactlyOnce() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                futures.add(pool.submit(() -> {
                    gate.await();
                    advancer.advance(runId);
                    return null;
                }));
            }
            gate.countDown();
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
        }

        assertThat(dispatchedProviderJobs).hasSize(2);
        assertThat(dispatchedProviderJobs).extracting(ProviderJob::stepOrder).containsExactlyInAnyOrder(5, 6);
    }

    @Test
    void firstErrorWinsSiblingCancelsWithRefundAndCompletedSiblingIsKept() {
        advancer.advance(runId);

        // Clip 6 fails first; run transitions to ERROR with clip 6's message.
        advancer.onRemoteCompleted(new MediaJobCompleted(
                clipError(orderedSteps.get(5), MediaError.Code.PROVIDER_REQUEST_FAILED, "clip 2 failed")));
        assertThat(run.getStatus()).isEqualTo(RunStatus.ERROR);
        assertThat(run.getErrorMessage()).isEqualTo("clip 2 failed");
        assertThat(jobControl.decide(runId, orderedSteps.get(4).getId(), 1))
                .isEqualTo(MediaControlResponse.Decision.CANCEL_SIBLING_FAILED);

        // Clip 5 returns sibling_failed: refunded and does not overwrite run error message.
        advancer.onRemoteCompleted(new MediaJobCompleted(
                clipError(orderedSteps.get(4), MediaError.Code.SIBLING_FAILED, "sibling failed")));
        assertThat(run.getErrorMessage()).isEqualTo("clip 2 failed");
        assertThat(orderedSteps.get(4).getErrorCode()).isEqualTo("sibling_failed");
        assertThat(creditEvents).contains("refund:" + orderedSteps.get(4).getId() + ":sibling_failed");
        assertThat(dispatchedMediaJobs).isEmpty();
    }

    @Test
    void completedSiblingBeforeCancelRemainsCompletedWhenOtherClipFails() {
        advancer.advance(runId);
        advancer.onRemoteCompleted(new MediaJobCompleted(clipSuccess(orderedSteps.get(4))));
        advancer.onRemoteCompleted(new MediaJobCompleted(
                clipError(orderedSteps.get(5), MediaError.Code.PROVIDER_TIMEOUT, "clip 2 timed out")));

        assertThat(run.getStatus()).isEqualTo(RunStatus.ERROR);
        assertThat(run.getErrorMessage()).isEqualTo("clip 2 timed out");
        assertThat(orderedSteps.get(4).getStatus()).isEqualTo(RunStepStatus.COMPLETED);
        assertThat(orderedSteps.get(5).getStatus()).isEqualTo(RunStepStatus.ERROR);
        assertThat(orderedSteps.get(6).getStatus()).isEqualTo(RunStepStatus.PENDING);
        assertThat(dispatchedMediaJobs).isEmpty();
    }

    @Test
    void insufficientCreditsOnFirstClipBlocksSecondClip() {
        when(creditAccounts.debitForStep(any(), any(), any(), anyShort(), any())).thenReturn(false);

        advancer.advance(runId);

        assertThat(dispatchedProviderJobs).isEmpty();
        assertThat(orderedSteps.get(4).getStatus()).isEqualTo(RunStepStatus.ERROR);
        assertThat(orderedSteps.get(5).getStatus()).isEqualTo(RunStepStatus.PENDING);
        assertThat(run.getStatus()).isEqualTo(RunStatus.ERROR);
    }

    @Test
    void insufficientCreditsOnSecondClipTriggersCancelForFirst() {
        when(creditAccounts.debitForStep(any(), any(), any(), anyShort(), any()))
                .thenReturn(true)
                .thenReturn(false);

        advancer.advance(runId);

        assertThat(dispatchedProviderJobs).extracting(ProviderJob::stepOrder).containsExactly(5);
        assertThat(orderedSteps.get(4).getStatus()).isEqualTo(RunStepStatus.RUNNING);
        assertThat(orderedSteps.get(5).getStatus()).isEqualTo(RunStepStatus.ERROR);
        assertThat(run.getStatus()).isEqualTo(RunStatus.ERROR);
        assertThat(jobControl.decide(runId, orderedSteps.get(4).getId(), 1))
                .isEqualTo(MediaControlResponse.Decision.CANCEL_SIBLING_FAILED);
    }

    @Test
    void stopRunSkipsPendingRenderAndSettlesCancelledOnlyAfterBothRunningClipsFinish() {
        advancer.advance(runId);

        cancellation.cancelRun(ownerId, runId);
        assertThat(run.getStatus()).isEqualTo(RunStatus.CANCELLING);
        assertThat(orderedSteps.get(6).getStatus()).isEqualTo(RunStepStatus.SKIPPED);
        // While neither clip failed, active clips continue to settle cleanly.
        assertThat(jobControl.decide(runId, orderedSteps.get(4).getId(), 1))
                .isEqualTo(MediaControlResponse.Decision.CONTINUE);

        advancer.onRemoteCompleted(new MediaJobCompleted(clipSuccess(orderedSteps.get(4))));
        assertThat(run.getStatus()).isEqualTo(RunStatus.CANCELLING);

        advancer.onRemoteCompleted(new MediaJobCompleted(clipSuccess(orderedSteps.get(5))));
        assertThat(run.getStatus()).isEqualTo(RunStatus.CANCELLED);
        assertThat(dispatchedMediaJobs).isEmpty();
    }
}
