package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.credit.api.CreditAccounts;
import com.flowcortex.credit.api.InsufficientCreditsException;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.workflow.api.IterateStepConfig;
import com.flowcortex.workflow.api.RunView;
import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The writes and the checks around them that {@link IterateRegenerateRules} does not decide: the kill
 * switch, availability, price, the cap and the guarded re-arm taken under the run lock.
 */
class IterateRegenerationTest {

    private static final String CONFIG = "{\"child\":{\"type\":\"echo\"},\"concurrency\":3}";

    private final WorkflowRunRepository runs = mock(WorkflowRunRepository.class);
    private final RunStepRepository steps = mock(RunStepRepository.class);
    private final StepExecutorRegistry registry = mock(StepExecutorRegistry.class);
    private final MediaJobs mediaJobs = mock(MediaJobs.class);
    private final CreditAccounts credits = mock(CreditAccounts.class);
    private final RunAdvancer advancer = mock(RunAdvancer.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final RunUpdates runUpdates = mock(RunUpdates.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    private final UUID ownerId = UUID.randomUUID();
    private final WorkflowRun run = new WorkflowRun(UUID.randomUUID(), UUID.randomUUID(), ownerId, "in",
            Instant.now());
    private final RunStep parent = new RunStep(UUID.randomUUID(), run.getId(), (short) 1, "iterate", null, CONFIG,
            null);
    private final RunStep item = RunStep.child(run.getId(), (short) 1, "echo", parent.getId(), (short) 1, (short) 0,
            "a");

    private IterateRegeneration service(boolean workflowEnabled) {
        return new IterateRegeneration(runs, steps, registry, mediaJobs, credits, advancer,
                new TransactionTemplate(transactionManager), runUpdates, events, workflowEnabled);
    }

    /** A completed run whose completed iterate step has one completed item, priced at 1 with credits on. */
    private void givenCompletedStep() {
        run.markCompleted(Instant.now());
        parent.markRunning(Instant.now());
        parent.markCompleted("[]", "{}", Instant.now());
        item.markRunning(Instant.now());
        item.markCompleted("a", "A", Instant.now());
        when(runs.findByIdAndOwnerId(run.getId(), ownerId)).thenReturn(Optional.of(run));
        when(steps.findByRunIdOrderByStepOrderAsc(run.getId())).thenReturn(List.of(parent));
        when(steps.findChildren(parent.getId(), (short) 1)).thenReturn(List.of(item));
        when(advancer.effectiveConcurrency(any(IterateStepConfig.class))).thenReturn(3);
        when(registry.isAvailable("echo")).thenReturn(true);
        when(credits.enabled()).thenReturn(true);
        when(credits.balance(ownerId)).thenReturn(10L);
        when(credits.estimate(List.of("echo"))).thenReturn(1L);
        when(steps.reArmChild(item.getId(), (short) 1)).thenReturn(1);
        when(steps.countActiveChildren(parent.getId(), (short) 1)).thenReturn(1L);
    }

    @Test
    void workflowsSwitchedOffIs503BeforeAnyRead() {
        assertThatThrownBy(() -> service(false).regenerate(ownerId, run.getId(), parent.getId(), 0))
                .isInstanceOfSatisfying(LocalizedStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(503));
        verify(runs, never()).findByIdAndOwnerId(any(), any());
    }

    @Test
    void anItemTypeThatIsOffIs503AndWritesNothing() {
        givenCompletedStep();
        when(registry.isAvailable("echo")).thenReturn(false);

        assertThatThrownBy(() -> service(true).regenerate(ownerId, run.getId(), parent.getId(), 0))
                .isInstanceOf(DubDisabledException.class);
        verify(steps, never()).reArmChild(any(), anyShort());
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void aBalanceBelowThePriceIs402AndWritesNothing() {
        givenCompletedStep();
        when(credits.balance(ownerId)).thenReturn(0L);

        assertThatThrownBy(() -> service(true).regenerate(ownerId, run.getId(), parent.getId(), 0))
                .isInstanceOf(InsufficientCreditsException.class);
        verify(steps, never()).reArmChild(any(), anyShort());
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void creditsSwitchedOffAsksForNoBalance() {
        givenCompletedStep();
        when(credits.enabled()).thenReturn(false);
        when(runs.findByIdAndOwnerId(run.getId(), ownerId)).thenReturn(Optional.of(run));

        service(true).regenerate(ownerId, run.getId(), parent.getId(), 0);

        verify(credits, never()).balance(any());
        verify(steps).reArmChild(item.getId(), (short) 1);
    }

    @Test
    void aReArmThatFindsTheItemMovedIsAChildRunningConflict() {
        givenCompletedStep();
        when(steps.reArmChild(item.getId(), (short) 1)).thenReturn(0);

        assertThatThrownBy(() -> service(true).regenerate(ownerId, run.getId(), parent.getId(), 0))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getDetail().code()).isEqualTo("workflow.iterate.childRunning"));
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void moreActiveItemsThanTheCapUnderTheLockIsABusyConflictAndRollsBack() {
        givenCompletedStep();
        when(steps.countActiveChildren(parent.getId(), (short) 1)).thenReturn(4L);

        assertThatThrownBy(() -> service(true).regenerate(ownerId, run.getId(), parent.getId(), 0))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getDetail().code()).isEqualTo("workflow.iterate.busy"));
        verify(transactionManager).rollback(any());
        verify(events, never()).publishEvent(any(Object.class));
        verify(runUpdates, never()).touched(any());
    }

    @Test
    void aRegenerateReArmsThenPublishesTheDispatchOnce() {
        givenCompletedStep();

        RunView view = service(true).regenerate(ownerId, run.getId(), parent.getId(), 0);

        verify(runs).lockById(run.getId());
        verify(steps).reArmChild(item.getId(), (short) 1);
        verify(runUpdates).touched(run.getId());
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue()).isEqualTo(new IterateChildRegenerated(run.getId(), item.getId()));
        assertThat(view.id()).isEqualTo(run.getId());
    }

    @Test
    void aPendingItemIsDispatchedAgainWithoutAReArm() {
        givenCompletedStep();
        RunStep pending = RunStep.child(run.getId(), (short) 1, "echo", parent.getId(), (short) 1, (short) 0, "a");
        when(steps.findChildren(parent.getId(), (short) 1)).thenReturn(List.of(pending));

        service(true).regenerate(ownerId, run.getId(), parent.getId(), 0);

        verify(steps, never()).reArmChild(any(), anyShort());
        verify(events).publishEvent(new IterateChildRegenerated(run.getId(), pending.getId()));
    }
}
