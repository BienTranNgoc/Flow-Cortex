package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.credit.api.CreditAccounts;
import com.flowcortex.media.api.MediaAssetInput;
import com.flowcortex.media.api.MediaJobInput;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.media.api.MediaOutput;
import com.flowcortex.media.api.UploadRejectedException;
import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.workflow.api.ProductVideoWorkflow;
import com.flowcortex.workflow.api.RunStartGuard.StartContext;
import com.flowcortex.workflow.api.RunStartGuard.StepDef;
import com.flowcortex.workflow.api.RunView;
import com.flowcortex.workflow.domain.RunStatus;
import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.domain.RunStepStatus;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Retry, reset and execute of a failed run's step against a real Postgres on the Flyway-migrated
 * schema. Not wrapped in a test transaction: each operation commits for real, and the rows are
 * seeded through the same transitions a run goes through.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.flyway.locations=filesystem:${flowcortex.migrations.dir}",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({RunResumption.class, RunStepTransactions.class, RunResumptionIT.Recorder.class})
@Testcontainers
class RunResumptionIT {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REMOTE = "remote";
    private static final String LOCAL = "echo";

    /** Collects the events the operations publish; the real listener (the advancer) is not part of this slice. */
    static class Recorder {
        final List<WorkflowRunResumedEvent> resumed = new CopyOnWriteArrayList<>();
        final List<WorkflowRunStartedEvent> started = new CopyOnWriteArrayList<>();

        @EventListener
        void on(WorkflowRunStartedEvent event) {
            started.add(event);
        }

        @EventListener
        void on(WorkflowRunResumedEvent event) {
            resumed.add(event);
        }
    }

    @MockitoBean
    private CreditAccounts creditAccounts;

    @MockitoBean
    private MediaJobs mediaJobs;

    @MockitoBean
    private StepExecutorRegistry registry;

    @MockitoBean
    private RunUpdates runUpdates;

    @MockitoBean
    private RunStartGuards runStartGuards;

    @Autowired
    private RunResumption resumption;

    @Autowired
    private RunStepTransactions transactions;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private WorkflowRunRepository workflowRunRepository;

    @Autowired
    private RunStepRepository runStepRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private Recorder recorder;

    @Autowired
    private DataSource dataSource;

    /** A run's id, its owner and its steps in order. */
    private record Seed(UUID ownerId, UUID runId, List<UUID> stepIds) {
    }

    @BeforeEach
    void stubCollaborators() {
        recorder.resumed.clear();
        recorder.started.clear();
        when(mediaJobs.copyStepOutputs(any(), any(), anyInt(), any())).thenAnswer(call -> {
            UUID from = call.getArgument(0);
            UUID to = call.getArgument(1);
            List<MediaOutput> outputs = call.getArgument(3);
            return outputs.stream()
                    .map(o -> new MediaOutput(o.name(), o.bucket(),
                            o.objectKey().replace("runs/" + from + "/", "runs/" + to + "/"), o.mime(), o.bytes(),
                            o.meta()))
                    .toList();
        });
        when(mediaJobs.copyRunInputs(any(), any())).thenAnswer(call -> {
            UUID to = call.getArgument(0);
            List<MediaAssetInput> inputs = call.getArgument(1);
            return inputs.stream()
                    .map(i -> new MediaAssetInput(i.assetId(), new MediaJobInput(i.input().key(),
                            i.input().bucket(), "runs/" + to + "/inputs/" + i.input().key())))
                    .toList();
        });
        when(registry.isAvailable(anyString())).thenReturn(true);
        when(registry.isRemote(anyString())).thenAnswer(call -> REMOTE.equals(call.getArgument(0)));
        when(mediaJobs.isEnabled()).thenReturn(true);
        when(mediaJobs.boundRunInputs(any())).thenReturn(List.of());
        when(mediaJobs.runInputsStored(any())).thenReturn(true);
        when(creditAccounts.enabled()).thenReturn(true);
        when(creditAccounts.debitForStep(any(), any(), any(), anyShort(), anyString())).thenReturn(true);
    }

    /** A run of {@code types} whose first {@code completed} steps completed and whose next one failed with {@code code}. */
    private Seed failedRun(String code, int completed, String... types) {
        UUID ownerId = UUID.randomUUID();
        Workflow workflow = workflowRepository.saveAndFlush(
                new Workflow(UUID.randomUUID(), ownerId, "it-" + UUID.randomUUID(), false));
        UUID runId = UUID.randomUUID();
        workflowRunRepository.saveAndFlush(new WorkflowRun(runId, workflow.getId(), ownerId, "in", NOW));
        List<UUID> stepIds = new ArrayList<>();
        for (int index = 0; index < types.length; index++) {
            UUID stepId = UUID.randomUUID();
            stepIds.add(stepId);
            runStepRepository.saveAndFlush(new RunStep(stepId, runId, (short) (index + 1), types[index], null,
                    configOf(types[index], index), null));
        }
        for (int index = 0; index < completed; index++) {
            claim(runId, stepIds.get(index), (short) 1, "resolved");
            transactions.complete(runId, stepIds.get(index), (short) 1, "out-" + (index + 1),
                    outputsOf(runId, index + 1), NOW);
        }
        if (completed < types.length) {
            claim(runId, stepIds.get(completed), (short) 1, "resolved text");
            transactions.fail(runId, stepIds.get(completed), (short) 1, code, "boom", NOW);
        }
        return new Seed(ownerId, runId, stepIds);
    }

    private static String configOf(String type, int index) {
        return REMOTE.equals(type) && index == 1
                ? "{\"outputInputs\":[{\"stepOrder\":1,\"outputName\":\"a.json\",\"inputKey\":\"first\"}]}"
                : null;
    }

    private static String outputsOf(UUID runId, int order) {
        return "[{\"name\":\"a.json\",\"bucket\":\"flowcortex-media\",\"objectKey\":\"runs/" + runId + "/steps/"
                + order + "/attempts/1/a.json\",\"mime\":\"application/json\",\"bytes\":2}]";
    }

    private void claim(UUID runId, UUID stepId, short attempt, String resolvedInput) {
        transactions.claim(runId, stepId, attempt, NOW, NOW.plusSeconds(1800), resolvedInput, () -> { });
    }

    private RunStep step(Seed seed, int index) {
        return runStepRepository.findById(seed.stepIds().get(index)).orElseThrow();
    }

    private WorkflowRun run(Seed seed) {
        return workflowRunRepository.findById(seed.runId()).orElseThrow();
    }

    private String snapshot(Seed seed) {
        StringBuilder out = new StringBuilder(run(seed).getStatus() + "/" + run(seed).getErrorMessage());
        for (int index = 0; index < seed.stepIds().size(); index++) {
            RunStep step = step(seed, index);
            out.append(" | ").append(step.getStatus()).append(',').append(step.getAttempt()).append(',')
                    .append(step.getErrorCode()).append(',').append(step.getInput());
        }
        return out.toString();
    }

    private void setAttempt(UUID stepId, int attempt) throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "UPDATE run_step SET attempt = ? WHERE id = ?")) {
            statement.setInt(1, attempt);
            statement.setObject(2, stepId);
            statement.executeUpdate();
        }
    }

    private static String conflictKey(Throwable thrown) {
        assertThat(thrown).isInstanceOf(ConflictException.class);
        return ((ConflictException) thrown).getDetail().code();
    }

    private static List<MediaAssetInput> bound(UUID runId, String... keys) {
        List<MediaAssetInput> inputs = new ArrayList<>();
        for (String key : keys) {
            inputs.add(new MediaAssetInput(UUID.randomUUID(),
                    new MediaJobInput(key, "flowcortex-media", "runs/" + runId + "/inputs/" + key)));
        }
        return inputs;
    }

    @Test
    void retryReArmsTheFailedStepAndReopensTheRun() {
        Seed seed = failedRun("boom_code", 1, LOCAL, LOCAL, LOCAL);

        RunView view = resumption.retryStep(seed.ownerId(), seed.runId(), seed.stepIds().get(1));

        RunStep retried = step(seed, 1);
        assertThat(retried.getStatus()).isEqualTo(RunStepStatus.PENDING);
        assertThat(retried.getAttempt()).isEqualTo((short) 2);
        assertThat(retried.getErrorCode()).isNull();
        assertThat(retried.getErrorMessage()).isNull();
        assertThat(retried.getOutput()).isNull();
        assertThat(retried.getOutputs()).isNull();
        assertThat(retried.getStartedAt()).isNull();
        assertThat(retried.getFinishedAt()).isNull();
        assertThat(retried.getPickedUpAt()).isNull();
        assertThat(retried.getQueueDeadline()).isNull();
        assertThat(retried.getRunDeadline()).isNull();
        assertThat(retried.getLeaseUntil()).isNull();
        assertThat(retried.getDeliveryCount()).isZero();
        assertThat(step(seed, 0).getStatus()).isEqualTo(RunStepStatus.COMPLETED);
        WorkflowRun reopened = run(seed);
        assertThat(reopened.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(reopened.getErrorMessage()).isNull();
        assertThat(reopened.getFinishedAt()).isNull();
        assertThat(view.status()).isEqualTo("running");
        assertThat(recorder.resumed).extracting(WorkflowRunResumedEvent::runId).containsExactly(seed.runId());
    }

    @Test
    void resetReArmsTheStepAndLeavesTheRunFailed() {
        Seed seed = failedRun("boom_code", 0, LOCAL, LOCAL);

        RunView view = resumption.resetStep(seed.ownerId(), seed.runId(), seed.stepIds().getFirst());

        assertThat(step(seed, 0).getStatus()).isEqualTo(RunStepStatus.PENDING);
        assertThat(step(seed, 0).getAttempt()).isEqualTo((short) 2);
        assertThat(run(seed).getStatus()).isEqualTo(RunStatus.ERROR);
        assertThat(view.status()).isEqualTo("error");
        assertThat(recorder.resumed).isEmpty();
    }

    @Test
    void executeAfterResetReopensAndPublishes() {
        Seed seed = failedRun("boom_code", 0, LOCAL, LOCAL);
        resumption.resetStep(seed.ownerId(), seed.runId(), seed.stepIds().getFirst());

        RunView view = resumption.executeStep(seed.ownerId(), seed.runId(), seed.stepIds().getFirst());

        assertThat(run(seed).getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(run(seed).getErrorMessage()).isNull();
        assertThat(view.status()).isEqualTo("running");
        assertThat(step(seed, 0).getAttempt()).isEqualTo((short) 2);
        assertThat(recorder.resumed).hasSize(1);
    }

    @Test
    void executeOnARunningRunWritesNothingAndPublishes() {
        Seed seed = failedRun("boom_code", 0, LOCAL, LOCAL);
        resumption.resetStep(seed.ownerId(), seed.runId(), seed.stepIds().getFirst());
        resumption.executeStep(seed.ownerId(), seed.runId(), seed.stepIds().getFirst());
        recorder.resumed.clear();
        String before = snapshot(seed);

        resumption.executeStep(seed.ownerId(), seed.runId(), seed.stepIds().getFirst());

        assertThat(snapshot(seed)).isEqualTo(before);
        assertThat(recorder.resumed).hasSize(1);
    }

    @Test
    void aForeignRunIsNotFoundForEveryOperation() {
        Seed seed = failedRun("boom_code", 0, LOCAL);
        UUID stranger = UUID.randomUUID();
        UUID stepId = seed.stepIds().getFirst();
        String before = snapshot(seed);

        assertThatThrownBy(() -> resumption.retryStep(stranger, seed.runId(), stepId))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> resumption.resetStep(stranger, seed.runId(), stepId))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> resumption.executeStep(stranger, seed.runId(), stepId))
                .isInstanceOf(NotFoundException.class);
        assertThat(snapshot(seed)).isEqualTo(before);
    }

    @Test
    void aStepOfAnotherRunIsNotFound() {
        Seed mine = failedRun("boom_code", 0, LOCAL);
        Seed other = failedRun("boom_code", 0, LOCAL);
        UUID foreignStep = other.stepIds().getFirst();

        assertThatThrownBy(() -> resumption.retryStep(mine.ownerId(), mine.runId(), foreignStep))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> resumption.resetStep(mine.ownerId(), mine.runId(), foreignStep))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> resumption.executeStep(mine.ownerId(), mine.runId(), foreignStep))
                .isInstanceOf(NotFoundException.class);
        assertThat(step(other, 0).getAttempt()).isEqualTo((short) 1);
    }

    @Test
    void refusalsChangeNothing() throws Exception {
        Seed completedRun = failedRun("x", 2, LOCAL, LOCAL);
        assertRefused(completedRun, 0, "retry", "workflow.run.notFailed");
        assertRefused(completedRun, 0, "execute", "workflow.run.notResumable");

        Seed laterStep = failedRun("boom_code", 0, LOCAL, LOCAL);
        assertRefused(laterStep, 1, "retry", "workflow.runStep.notFailed");
        assertRefused(laterStep, 1, "reset", "workflow.runStep.notFailed");
        assertRefused(laterStep, 1, "execute", "workflow.runStep.notNext");
        assertRefused(laterStep, 0, "execute", "workflow.runStep.notPending");

        Seed rejected = failedRun("input_rejected", 0, LOCAL);
        assertRefused(rejected, 0, "retry", "workflow.runStep.notRetryable");
        assertRefused(rejected, 0, "reset", "workflow.runStep.notRetryable");

        Seed capped = failedRun("boom_code", 0, LOCAL);
        setAttempt(capped.stepIds().getFirst(), Short.MAX_VALUE);
        assertRefused(capped, 0, "retry", "workflow.runStep.attemptLimit");
        assertRefused(capped, 0, "reset", "workflow.runStep.attemptLimit");

        Seed running = failedRun("boom_code", 0, LOCAL);
        resumption.retryStep(running.ownerId(), running.runId(), running.stepIds().getFirst());
        claim(running.runId(), running.stepIds().getFirst(), (short) 2, "resolved");
        assertRefused(running, 0, "retry", "workflow.run.notFailed");
        assertRefused(running, 0, "reset", "workflow.run.notFailed");
    }

    private void assertRefused(Seed seed, int stepIndex, String operation, String expectedKey) {
        String before = snapshot(seed);
        UUID stepId = seed.stepIds().get(stepIndex);
        recorder.resumed.clear();

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> {
            switch (operation) {
                case "retry" -> resumption.retryStep(seed.ownerId(), seed.runId(), stepId);
                case "reset" -> resumption.resetStep(seed.ownerId(), seed.runId(), stepId);
                default -> resumption.executeStep(seed.ownerId(), seed.runId(), stepId);
            }
        });

        assertThat(conflictKey(thrown)).as(operation + " of step " + stepIndex).isEqualTo(expectedKey);
        assertThat(snapshot(seed)).isEqualTo(before);
        assertThat(recorder.resumed).isEmpty();
    }

    @Test
    void theSwitchesRefuseBeforeAnyWrite() {
        Seed seed = failedRun("boom_code", 0, REMOTE);
        UUID stepId = seed.stepIds().getFirst();
        String before = snapshot(seed);

        RunResumption disabled = new RunResumption(workflowRepository, workflowRunRepository,
                runStepRepository, registry, mediaJobs, transactionTemplate, runUpdates, runStartGuards, events,
                false);
        assertThatThrownBy(() -> disabled.retryStep(seed.ownerId(), seed.runId(), stepId))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        assertThat(snapshot(seed)).isEqualTo(before);

        when(mediaJobs.isEnabled()).thenReturn(false);
        assertThatThrownBy(() -> resumption.retryStep(seed.ownerId(), seed.runId(), stepId))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        assertThat(snapshot(seed)).isEqualTo(before);

        resumption.resetStep(seed.ownerId(), seed.runId(), stepId);
        String afterReset = snapshot(seed);
        assertThatThrownBy(() -> resumption.executeStep(seed.ownerId(), seed.runId(), stepId))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        assertThat(snapshot(seed)).isEqualTo(afterReset);

        when(mediaJobs.isEnabled()).thenReturn(true);
        when(registry.isAvailable(REMOTE)).thenReturn(false);
        assertThatThrownBy(() -> resumption.executeStep(seed.ownerId(), seed.runId(), stepId))
                .isInstanceOf(DubDisabledException.class);
        assertThat(snapshot(seed)).isEqualTo(afterReset);
        assertThat(recorder.resumed).isEmpty();
    }

    @Test
    void twoConcurrentRetriesHaveOneWinner() throws Exception {
        Seed seed = failedRun("boom_code", 0, LOCAL);
        UUID stepId = seed.stepIds().getFirst();
        CountDownLatch go = new CountDownLatch(1);
        Callable<Object> retry = () -> {
            go.await();
            return resumption.retryStep(seed.ownerId(), seed.runId(), stepId);
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = pool.submit(retry);
            Future<Object> second = pool.submit(retry);
            go.countDown();
            List<Throwable> failures = new ArrayList<>();
            int wins = 0;
            for (Future<Object> future : List.of(first, second)) {
                try {
                    future.get();
                    wins++;
                } catch (ExecutionException e) {
                    failures.add(e.getCause());
                }
            }

            assertThat(wins).isEqualTo(1);
            assertThat(failures).hasSize(1);
            assertThat(conflictKey(failures.getFirst()))
                    .isIn("workflow.runStep.notFailed", "workflow.run.notFailed");
            assertThat(step(seed, 0).getAttempt()).isEqualTo((short) 2);
            assertThat(recorder.resumed).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aRetriedAttemptIsChargedWithItsAttempt() {
        Seed seed = failedRun("boom_code", 0, LOCAL);
        UUID stepId = seed.stepIds().getFirst();
        resumption.retryStep(seed.ownerId(), seed.runId(), stepId);

        claim(seed.runId(), stepId, step(seed, 0).getAttempt(), "resolved");

        verify(creditAccounts).debitForStep(any(), eq(seed.runId()), eq(stepId), eq((short) 2), eq(LOCAL));
        assertThat(step(seed, 0).getStatus()).isEqualTo(RunStepStatus.RUNNING);
    }

    @Test
    void retryRestoresTheStartsStoredInput() throws Exception {
        Seed seed = failedRun("boom_code", 0, REMOTE, LOCAL);
        when(mediaJobs.boundRunInputs(seed.runId())).thenReturn(bound(seed.runId(), "media"));
        assertThat(step(seed, 0).getInput()).isEqualTo("resolved text");

        resumption.retryStep(seed.ownerId(), seed.runId(), seed.stepIds().getFirst());

        assertThat(JSON.readTree(step(seed, 0).getInput()).isObject()).isTrue();
        assertThat(JSON.readTree(step(seed, 0).getInput()).get("key").asText()).isEqualTo("media");

        Seed local = failedRun("boom_code", 0, LOCAL);
        when(mediaJobs.boundRunInputs(local.runId())).thenReturn(bound(local.runId(), "media"));
        resumption.retryStep(local.ownerId(), local.runId(), local.stepIds().getFirst());
        assertThat(step(local, 0).getInput()).isNull();
    }

    @Test
    void retryOfAnOutputInputsStepResolvesOnce() throws Exception {
        Seed seed = failedRun("contract_violation", 1, REMOTE, REMOTE);
        when(mediaJobs.boundRunInputs(seed.runId())).thenReturn(bound(seed.runId(), "media"));
        assertThat(step(seed, 1).getInput()).isEqualTo("resolved text");

        resumption.retryStep(seed.ownerId(), seed.runId(), seed.stepIds().get(1));

        String resolved = new StoredWithOutputsResolver().resolve(step(seed, 1).getInput(), configOf(REMOTE, 1),
                Map.of(1, outputsOf(seed.runId(), 1)), seed.runId(), 2);
        List<MediaJobInput> inputs = JSON.readValue(resolved, new TypeReference<>() { });
        assertThat(inputs).extracting(MediaJobInput::key).containsExactly("media", "first");
    }

    @Test
    void retryOfAProviderStepWithAReferenceImageSendsTheRunInput() throws Exception {
        Seed seed = failedRun("boom_code", 0, REMOTE);
        when(mediaJobs.boundRunInputs(seed.runId())).thenReturn(bound(seed.runId(), "image_1", "image_2"));

        resumption.retryStep(seed.ownerId(), seed.runId(), seed.stepIds().getFirst());

        List<MediaJobInput> inputs = JSON.readValue(step(seed, 0).getInput(), new TypeReference<>() { });
        assertThat(inputs).extracting(MediaJobInput::key).containsExactly("image_1", "image_2");
        assertThat(inputs.get(1).objectKey()).isEqualTo("runs/" + seed.runId() + "/inputs/image_2");
    }

    @Test
    void aGoneRunInputRefusesRetryAndExecute() {
        Seed seed = failedRun("boom_code", 0, REMOTE);
        UUID stepId = seed.stepIds().getFirst();
        when(mediaJobs.boundRunInputs(seed.runId())).thenReturn(bound(seed.runId(), "media"));
        when(mediaJobs.runInputsStored(seed.runId())).thenReturn(false);
        String before = snapshot(seed);

        assertThatThrownBy(() -> resumption.retryStep(seed.ownerId(), seed.runId(), stepId))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.getDetail().code()).isEqualTo("workflow.run.retry.objectGone");
                    assertThat(e.getDetail().args()).containsExactly(0);
                });
        assertThat(snapshot(seed)).isEqualTo(before);

        resumption.resetStep(seed.ownerId(), seed.runId(), stepId);
        assertThatThrownBy(() -> resumption.executeStep(seed.ownerId(), seed.runId(), stepId))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getDetail().code()).isEqualTo("workflow.run.retry.objectGone"));
        assertThat(step(seed, 0).getAttempt()).isEqualTo((short) 2);
        assertThat(run(seed).getStatus()).isEqualTo(RunStatus.ERROR);
    }

    private List<RunStep> stepsOf(UUID runId) {
        return runStepRepository.findByRunIdOrderByStepOrderAsc(runId);
    }

    private void setWorkflow(UUID runId, UUID workflowId) throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "UPDATE workflow_run SET workflow_id = ? WHERE id = ?")) {
            statement.setObject(1, workflowId);
            statement.setObject(2, runId);
            statement.executeUpdate();
        }
    }

    @Test
    void aRunRetryReusesCompletedStepsAndLeavesTheOriginal() {
        Seed seed = failedRun("boom_code", 1, LOCAL, LOCAL, LOCAL);
        String before = snapshot(seed);
        long runsBefore = workflowRunRepository.count();

        RunView view = resumption.retryRun(seed.ownerId(), seed.runId(), null, false);

        assertThat(view.id()).isNotEqualTo(seed.runId());
        WorkflowRun original = run(seed);
        WorkflowRun created = workflowRunRepository.findById(view.id()).orElseThrow();
        assertThat(workflowRunRepository.count()).isEqualTo(runsBefore + 1);
        assertThat(created.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(created.getOwnerId()).isEqualTo(seed.ownerId());
        assertThat(created.getWorkflowId()).isEqualTo(original.getWorkflowId());
        assertThat(created.getInput()).isEqualTo(original.getInput());
        assertThat(created.getOptions()).isEqualTo(original.getOptions());
        List<RunStep> steps = stepsOf(view.id());
        assertThat(steps).extracting(RunStep::getStatus)
                .containsExactly(RunStepStatus.COMPLETED, RunStepStatus.PENDING, RunStepStatus.PENDING);
        RunStep reused = steps.getFirst();
        assertThat(reused.getAttempt()).isEqualTo((short) 1);
        assertThat(reused.getInput()).isEqualTo("resolved");
        assertThat(reused.getOutput()).isEqualTo("out-1");
        assertThat(reused.getOutputs()).contains("runs/" + view.id() + "/steps/1/attempts/1/a.json")
                .doesNotContain(seed.runId().toString());
        assertThat(reused.getFinishedAt()).isEqualTo(step(seed, 0).getFinishedAt());
        assertThat(steps.get(1).getType()).isEqualTo(LOCAL);
        assertThat(steps.get(1).getConfig()).isEqualTo(step(seed, 1).getConfig());
        assertThat(steps.get(1).getAttempt()).isEqualTo((short) 1);
        assertThat(snapshot(seed)).isEqualTo(before);
        assertThat(recorder.started).extracting(WorkflowRunStartedEvent::runId).containsExactly(view.id());
        verify(creditAccounts, never()).debitForStep(any(), eq(view.id()), any(), anyShort(), anyString());
    }

    @Test
    void remoteStepsGetTheCopiedRunInputsByTheStartsShapeRule() throws Exception {
        Seed single = failedRun("boom_code", 1, REMOTE, REMOTE);
        when(mediaJobs.boundRunInputs(single.runId())).thenReturn(bound(single.runId(), "media"));
        RunView singleView = resumption.retryRun(single.ownerId(), single.runId(), null, false);

        MediaJobInput object = JSON.readValue(stepsOf(singleView.id()).get(1).getInput(), MediaJobInput.class);
        assertThat(object.key()).isEqualTo("media");
        assertThat(object.objectKey()).isEqualTo("runs/" + singleView.id() + "/inputs/media");
        assertThat(stepsOf(singleView.id()).getFirst().getInput()).isEqualTo("resolved");
        ArgumentCaptor<List<MediaAssetInput>> copies = ArgumentCaptor.forClass(List.class);
        verify(mediaJobs).bindRunInputs(eq(singleView.id()), copies.capture());
        assertThat(copies.getValue()).extracting(i -> i.input().objectKey())
                .containsExactly("runs/" + singleView.id() + "/inputs/media");

        Seed images = failedRun("boom_code", 1, REMOTE, REMOTE);
        when(mediaJobs.boundRunInputs(images.runId())).thenReturn(bound(images.runId(), "image_1"));
        RunView imagesView = resumption.retryRun(images.ownerId(), images.runId(), null, false);

        List<MediaJobInput> array = JSON.readValue(stepsOf(imagesView.id()).get(1).getInput(),
                new TypeReference<List<MediaJobInput>>() { });
        assertThat(array).extracting(MediaJobInput::key).containsExactly("image_1");
        assertThat(array.getFirst().objectKey()).isEqualTo("runs/" + imagesView.id() + "/inputs/image_1");
    }

    @Test
    void reusedRemoteOutputsAreRegisteredForTheNewStep() {
        Seed remote = failedRun("boom_code", 1, REMOTE, LOCAL);
        RunView view = resumption.retryRun(remote.ownerId(), remote.runId(), null, false);

        UUID newStepId = stepsOf(view.id()).getFirst().getId();
        ArgumentCaptor<List<MediaOutput>> registered = ArgumentCaptor.forClass(List.class);
        verify(mediaJobs).registerOutputs(eq(remote.ownerId()), eq(view.id()), eq(newStepId), eq(1), eq((short) 1),
                registered.capture());
        assertThat(registered.getValue()).extracting(MediaOutput::objectKey)
                .containsExactly("runs/" + view.id() + "/steps/1/attempts/1/a.json");

        Seed local = failedRun("boom_code", 1, LOCAL, LOCAL);
        RunView localView = resumption.retryRun(local.ownerId(), local.runId(), null, false);
        verify(mediaJobs, never()).registerOutputs(any(), eq(localView.id()), any(), anyInt(), anyShort(), any());
    }

    @Test
    void aGoneObjectRefusesWithItsStepAndCreatesNothing() {
        Seed seed = failedRun("boom_code", 2, LOCAL, LOCAL, LOCAL);
        doThrow(new UploadRejectedException("outputs", new LocalizedMessage("upload.notFound")))
                .when(mediaJobs).copyStepOutputs(eq(seed.runId()), any(), eq(2), any());
        long runsBefore = workflowRunRepository.count();

        assertThatThrownBy(() -> resumption.retryRun(seed.ownerId(), seed.runId(), 3, false))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.getDetail().code()).isEqualTo("workflow.run.retry.objectGone");
                    assertThat(e.getDetail().args()).containsExactly(2);
                });
        assertThat(workflowRunRepository.count()).isEqualTo(runsBefore);
        assertThat(recorder.started).isEmpty();
    }

    @Test
    void startGuardsSeeOnlyTheReRunSteps() {
        Seed seed = failedRun("boom_code", 1, LOCAL, LOCAL, LOCAL);

        resumption.retryRun(seed.ownerId(), seed.runId(), null, true);

        ArgumentCaptor<List<StepDef>> defs = ArgumentCaptor.forClass(List.class);
        verify(runStartGuards).checkDefs(eq(seed.ownerId()), eq(run(seed).getWorkflowId()), defs.capture(),
                eq(new StartContext(true)));
        assertThat(defs.getValue()).extracting(StepDef::order).containsExactly(2, 3);
    }

    @Test
    void aProductVideoRunIsRefused() throws Exception {
        Seed seed = failedRun("boom_code", 0, LOCAL);
        setWorkflow(seed.runId(), ProductVideoWorkflow.ID);
        long runsBefore = workflowRunRepository.count();

        assertThatThrownBy(() -> resumption.retryRun(seed.ownerId(), seed.runId(), null, false))
                .isInstanceOfSatisfying(LocalizedStatusException.class, e -> {
                    assertThat(e.getStatusCode().value()).isEqualTo(400);
                    assertThat(e.getDetailMessageCode()).isEqualTo("workflow.run.retry.productVideo");
                });
        assertThat(workflowRunRepository.count()).isEqualTo(runsBefore);
    }

    @Test
    void aWorkflowNoLongerVisibleIsNotFound() throws Exception {
        Seed seed = failedRun("boom_code", 0, LOCAL);
        Workflow foreign = workflowRepository.saveAndFlush(
                new Workflow(UUID.randomUUID(), UUID.randomUUID(), "it-" + UUID.randomUUID(), false));
        setWorkflow(seed.runId(), foreign.getId());
        long runsBefore = workflowRunRepository.count();

        assertThatThrownBy(() -> resumption.retryRun(seed.ownerId(), seed.runId(), null, false))
                .isInstanceOfSatisfying(NotFoundException.class,
                        e -> assertThat(e.getDetail().code()).isEqualTo("workflow.notFound"));
        assertThat(workflowRunRepository.count()).isEqualTo(runsBefore);
    }

    @Test
    void aRunRetryRefusesWhatTheArbiterRefusesAndCreatesNothing() {
        Seed running = failedRun("boom_code", 0, LOCAL, LOCAL);
        resumption.retryStep(running.ownerId(), running.runId(), running.stepIds().getFirst());
        Seed failed = failedRun("boom_code", 1, LOCAL, LOCAL, LOCAL);
        long runsBefore = workflowRunRepository.count();

        assertThatThrownBy(() -> resumption.retryRun(running.ownerId(), running.runId(), null, false))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getDetail().code()).isEqualTo("workflow.run.notFinished"));
        assertThatThrownBy(() -> resumption.retryRun(failed.ownerId(), failed.runId(), 3, false))
                .isInstanceOfSatisfying(UnprocessableEntityException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("WORKFLOW_RETRY_FROM_INVALID");
                    assertThat(e.getErrors()).containsKey("fromStepOrder");
                });
        assertThat(workflowRunRepository.count()).isEqualTo(runsBefore);
    }
}
