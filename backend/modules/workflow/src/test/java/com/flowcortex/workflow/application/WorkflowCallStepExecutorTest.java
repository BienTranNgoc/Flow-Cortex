package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.credit.api.InsufficientCreditsException;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.workflow.api.ProductVideoWorkflow;
import com.flowcortex.workflow.api.RunOptions;
import com.flowcortex.workflow.api.RunStartGuard.StartContext;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.domain.WorkflowStep;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/** What a {@code workflow.call} step decides and creates in its claim, over mocked collaborators. */
class WorkflowCallStepExecutorTest {

    private static final Duration MAX_RUNTIME = Duration.ofHours(2);
    private static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");
    private static final String OPTIONS = "{\"voice\":\"nova\"}";

    private final WorkflowRunRepository runs = mock(WorkflowRunRepository.class);
    private final WorkflowRepository workflows = mock(WorkflowRepository.class);
    private final ServedDefinitions served = mock(ServedDefinitions.class);
    private final RunStartGuards guards = mock(RunStartGuards.class);
    private final StepExecutorRegistry registry = mock(StepExecutorRegistry.class);
    private final MediaJobs mediaJobs = mock(MediaJobs.class);
    private final RunCreation creation = mock(RunCreation.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

    {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    private final UUID owner =UUID.randomUUID();
    private final UUID parentWorkflowId = UUID.randomUUID();
    private final UUID childWorkflowId = UUID.randomUUID();
    private final UUID parentRunId = UUID.randomUUID();
    private final UUID stepId = UUID.randomUUID();
    private final Workflow child = new Workflow(childWorkflowId, UUID.randomUUID(), "Child", true);
    private final List<WorkflowStep> childSteps = List.of(
            new WorkflowStep(UUID.randomUUID(), childWorkflowId, (short) 1, "a", "echo", null),
            new WorkflowStep(UUID.randomUUID(), childWorkflowId, (short) 2, "b", "echo", null));

    @SuppressWarnings("unchecked")
    private WorkflowCallStepExecutor executor(boolean workflowsEnabled) {
        ObjectProvider<StepExecutorRegistry> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(registry);
        ObjectProvider<RunStartGuards> guardsProvider = mock(ObjectProvider.class);
        when(guardsProvider.getObject()).thenReturn(guards);
        return new WorkflowCallStepExecutor(runs, workflows, served, guardsProvider, provider, mediaJobs, creation,
                new TransactionTemplate(transactionManager), workflowsEnabled, MAX_RUNTIME);
    }

    private WorkflowRun parentAtDepth(int depth, UUID parentOfParent) {
        WorkflowRun parent = depth == 0
                ? new WorkflowRun(parentRunId, parentWorkflowId, owner, "in", OPTIONS, NOW)
                : WorkflowRun.child(parentRunId, parentWorkflowId, owner, "in", OPTIONS, NOW, parentOfParent,
                        UUID.randomUUID(), (short) 1, (short) depth);
        when(runs.findById(parentRunId)).thenReturn(Optional.of(parent));
        return parent;
    }

    private void givenValidChild() {
        when(workflows.findVisibleTo(childWorkflowId, owner)).thenReturn(Optional.of(child));
        when(served.stepsFor(child, owner)).thenReturn(childSteps);
        when(registry.isAvailable(any())).thenReturn(true);
        when(mediaJobs.isEnabled()).thenReturn(true);
    }

    private StepContext context(String config) {
        return new StepContext(parentRunId, stepId, 2, WorkflowCallStepExecutor.TYPE, (short) 3, "the input", config,
                null, RunOptions.DEFAULTS, new java.util.ArrayList<>());
    }

    private StepContext contextFor(UUID workflowId) {
        return context("{\"workflowId\":\"" + workflowId + "\"}");
    }

    private void assertFails(StepContext context, String code) {
        assertThatThrownBy(() -> executor(true).execute(context))
                .isInstanceOfSatisfying(StepFailedException.class, e -> assertThat(e.code()).isEqualTo(code));
        verify(creation, never()).create(any());
    }

    @Test
    void theChildIsCreatedForTheParentRunsOwnerWithTheServedStepsAndTheParentsLinks() {
        parentAtDepth(0, null);
        givenValidChild();

        String output = executor(true).execute(contextFor(childWorkflowId));

        assertThat(output).isNull();
        ArgumentCaptor<RunCreation.NewRun> created = ArgumentCaptor.forClass(RunCreation.NewRun.class);
        verify(creation).create(created.capture());
        RunCreation.NewRun run = created.getValue();
        assertThat(run.ownerId()).isEqualTo(owner);
        assertThat(run.workflowId()).isEqualTo(childWorkflowId);
        assertThat(run.steps()).isSameAs(childSteps);
        assertThat(run.input()).isEqualTo("the input");
        assertThat(run.optionsJson()).isEqualTo(OPTIONS);
        assertThat(run.parentRunId()).isEqualTo(parentRunId);
        assertThat(run.parentStepId()).isEqualTo(stepId);
        assertThat(run.parentStepAttempt()).isEqualTo((short) 3);
        assertThat(run.callDepth()).isEqualTo((short) 1);
        verify(guards).check(owner, childWorkflowId, childSteps, new StartContext(true));
    }

    @Test
    void aChildOfAChildIsOneLevelDeeper() {
        parentAtDepth(1, UUID.randomUUID());
        when(runs.findById(any())).thenAnswer(invocation -> {
            UUID id = invocation.getArgument(0);
            if (id.equals(parentRunId)) {
                return Optional.of(WorkflowRun.child(parentRunId, parentWorkflowId, owner, "in", OPTIONS, NOW,
                        UUID.randomUUID(), UUID.randomUUID(), (short) 1, (short) 1));
            }
            return Optional.of(new WorkflowRun(id, UUID.randomUUID(), owner, "in", OPTIONS, NOW));
        });
        givenValidChild();

        executor(true).execute(contextFor(childWorkflowId));

        ArgumentCaptor<RunCreation.NewRun> created = ArgumentCaptor.forClass(RunCreation.NewRun.class);
        verify(creation).create(created.capture());
        assertThat(created.getValue().callDepth()).isEqualTo((short) 2);
    }

    @Test
    void aChildNotVisibleToTheOwnerFailsNotFoundAndCreatesNothing() {
        parentAtDepth(0, null);
        when(workflows.findVisibleTo(childWorkflowId, owner)).thenReturn(Optional.empty());

        assertFails(contextFor(childWorkflowId), "workflow_call_not_found");
    }

    @Test
    void aWorkflowCallingItselfFailsAsACycleWithoutReadingTheChild() {
        parentAtDepth(0, null);

        assertFails(contextFor(parentWorkflowId), "workflow_call_cycle");
        verify(workflows, never()).findVisibleTo(any(), any());
    }

    @Test
    void aWorkflowCallingTheOneThatCallsItsParentFailsAsACycleWithoutReadingTheChild() {
        UUID grandparentRun = UUID.randomUUID();
        UUID workflowA = UUID.randomUUID();
        parentAtDepth(1, grandparentRun);
        when(runs.findById(grandparentRun))
                .thenReturn(Optional.of(new WorkflowRun(grandparentRun, workflowA, owner, "in", OPTIONS, NOW)));

        assertFails(contextFor(workflowA), "workflow_call_cycle");
        verify(workflows, never()).findVisibleTo(any(), any());
    }

    @Test
    void aParentAlreadyTwoLevelsDownIsRefusedTooDeep() {
        UUID grandparentRun = UUID.randomUUID();
        UUID greatGrandparentRun = UUID.randomUUID();
        parentAtDepth(2, grandparentRun);
        when(runs.findById(grandparentRun)).thenReturn(Optional.of(WorkflowRun.child(grandparentRun,
                UUID.randomUUID(), owner, "in", OPTIONS, NOW, greatGrandparentRun, UUID.randomUUID(), (short) 1,
                (short) 1)));
        when(runs.findById(greatGrandparentRun)).thenReturn(
                Optional.of(new WorkflowRun(greatGrandparentRun, UUID.randomUUID(), owner, "in", OPTIONS, NOW)));

        assertFails(contextFor(childWorkflowId), "workflow_call_too_deep");
        verify(workflows, never()).findVisibleTo(any(), any());
    }

    @Test
    void theProductVideoWorkflowIsUnsupported() {
        parentAtDepth(0, null);
        Workflow productVideo = new Workflow(ProductVideoWorkflow.ID, Workflow.SYSTEM_OWNER_ID, "Product Video", true);
        when(workflows.findVisibleTo(ProductVideoWorkflow.ID, owner)).thenReturn(Optional.of(productVideo));
        when(served.stepsFor(productVideo, owner)).thenReturn(childSteps);

        assertFails(contextFor(ProductVideoWorkflow.ID), "workflow_call_unsupported");
    }

    @Test
    void aChildStartingWithAnUploadStepIsRefused() {
        parentAtDepth(0, null);
        givenValidChild();
        when(registry.isRemote("echo")).thenReturn(true);

        assertFails(contextFor(childWorkflowId), "workflow_call_needs_upload");
    }

    @Test
    void whileWorkflowsAreSwitchedOffTheCallIsUnavailable() {
        parentAtDepth(0, null);
        givenValidChild();

        assertThatThrownBy(() -> executor(false).execute(contextFor(childWorkflowId)))
                .isInstanceOfSatisfying(StepFailedException.class,
                        e -> assertThat(e.code()).isEqualTo("workflow_call_unavailable"));
        verify(creation, never()).create(any());
    }

    @Test
    void aChildStepThatMayNotRunNowMakesTheCallUnavailable() {
        parentAtDepth(0, null);
        givenValidChild();
        when(registry.isAvailable("echo")).thenReturn(false);

        assertFails(contextFor(childWorkflowId), "workflow_call_unavailable");
    }

    @Test
    void aMemberWhoCannotPayForTheChildFailsInsufficientCreditsAndCreatesNothing() {
        parentAtDepth(0, null);
        givenValidChild();
        doThrow(new InsufficientCreditsException(0, 3)).when(guards).check(any(), any(), any(), any());

        assertFails(contextFor(childWorkflowId), InsufficientCreditsException.CODE);
        verify(guards).check(owner, childWorkflowId, childSteps, new StartContext(true));
    }

    @Test
    void anIncompleteChildFailsChildIncompleteAndCreatesNothing() {
        parentAtDepth(0, null);
        givenValidChild();
        doThrow(new UnprocessableEntityException(new LocalizedMessage("workflow.incomplete.noSteps"),
                WorkflowReadinessGuard.CODE)).when(guards).check(any(), any(), any(), any());

        assertFails(contextFor(childWorkflowId), "workflow_call_child_incomplete");
    }

    @Test
    void anyOtherRefusalOfTheGuardsIsReportedUnavailableNotRolledBack() {
        parentAtDepth(0, null);
        givenValidChild();
        doThrow(new UnprocessableEntityException(new LocalizedMessage("workflow.incomplete.noSteps"), "OTHER"))
                .when(guards).check(any(), any(), any(), any());

        assertFails(contextFor(childWorkflowId), "workflow_call_unavailable");
    }

    @Test
    void aConfigThatFailsTheRuntimeParseFailsInvalidConfig() {
        parentAtDepth(0, null);

        assertFails(context("{\"workflowId\":\"abc\"}"), "workflow_call_invalid_config");
        assertFails(context(null), "workflow_call_invalid_config");
        verify(runs, never()).findById(any());
    }

    @Test
    void servedDefinitionsRejectingTheWorkflowFailsUnavailableWithTheCauseKept() {
        parentAtDepth(0, null);
        when(workflows.findVisibleTo(childWorkflowId, owner)).thenReturn(Optional.of(child));
        IllegalArgumentException cause = new IllegalArgumentException("snapshot unreadable");
        when(served.stepsFor(child, owner)).thenThrow(cause);

        assertThatThrownBy(() -> executor(true).execute(contextFor(childWorkflowId)))
                .isInstanceOfSatisfying(StepFailedException.class, e -> {
                    assertThat(e.code()).isEqualTo("workflow_call_unavailable");
                    assertThat(e.getCause()).isSameAs(cause);
                });
        verify(creation, never()).create(any());
    }

    @Test
    void aFailedLookupOfTheChildFailsUnavailable() {
        parentAtDepth(0, null);
        when(workflows.findVisibleTo(childWorkflowId, owner))
                .thenThrow(new DataAccessResourceFailureException("connection lost"));

        assertFails(contextFor(childWorkflowId), "workflow_call_unavailable");
    }

    @Test
    void theFactsAreReadInTheirOwnReadOnlyTransactionThatRollsBackOnAFailure() {
        parentAtDepth(0, null);
        SimpleTransactionStatus status = new SimpleTransactionStatus();
        when(transactionManager.getTransaction(any())).thenReturn(status);
        when(workflows.findVisibleTo(childWorkflowId, owner)).thenThrow(new IllegalStateException("boom"));

        assertFails(contextFor(childWorkflowId), "workflow_call_unavailable");

        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager).getTransaction(definition.capture());
        assertThat(definition.getValue().getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(definition.getValue().isReadOnly()).isTrue();
        verify(transactionManager).rollback(status);
        verify(transactionManager, never()).commit(any());
    }

    @Test
    void aParentRunThatIsGoneFailsUnavailable() {
        when(runs.findById(parentRunId)).thenReturn(Optional.empty());

        assertFails(contextFor(childWorkflowId), "workflow_call_unavailable");
    }

    @Test
    void aFailureWhileCreatingTheChildIsNotHidden() {
        parentAtDepth(0, null);
        givenValidChild();
        IllegalStateException failure = new IllegalStateException("insert failed");
        when(creation.create(any())).thenThrow(failure);

        assertThatThrownBy(() -> executor(true).execute(contextFor(childWorkflowId))).isSameAs(failure);
    }

    @Test
    void theExecutorDescribesItselfAsANestedStepBoundedByTheConfiguredRuntime() {
        WorkflowCallStepExecutor executor = executor(true);

        assertThat(executor.type()).isEqualTo("workflow.call");
        assertThat(executor.mode()).isEqualTo(StepExecutor.Mode.NESTED);
        assertThat(executor.maxRuntime()).isEqualTo(MAX_RUNTIME);
        assertThat(executor.inputPolicy()).isEqualTo(StepExecutor.InputPolicy.RESOLVED);
    }
}
