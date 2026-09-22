package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.workflow.api.RunView;
import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.domain.WorkflowStep;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import com.flowcortex.workflow.infrastructure.WorkflowStepRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.server.ResponseStatusException;

/**
 * Task 5.4/5.5 — {@link WorkflowRunServiceImpl}: start-run tx#1 (design.md
 * D5/R-050), the kill switch (R-046), and owner-scoped reads (R-047).
 */
class WorkflowRunServiceImplTest {

    private final WorkflowRepository workflowRepository = mock(WorkflowRepository.class);
    private final WorkflowStepRepository workflowStepRepository = mock(WorkflowStepRepository.class);
    private final WorkflowRunRepository workflowRunRepository = mock(WorkflowRunRepository.class);
    private final RunStepRepository runStepRepository = mock(RunStepRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    private WorkflowRunServiceImpl newService(boolean workflowEnabled) {
        return new WorkflowRunServiceImpl(workflowRepository, workflowStepRepository, workflowRunRepository,
                runStepRepository, events, workflowEnabled);
    }

    @Test
    void startRunWhenDisabledThrows503AndCreatesNothing() {
        WorkflowRunServiceImpl service = newService(false);

        assertThatThrownBy(() -> service.startRun(UUID.randomUUID(), UUID.randomUUID(), "hi"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(503));

        verify(workflowRunRepository, never()).save(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void startRunOnInvisibleWorkflowIsNotFound() {
        WorkflowRunServiceImpl service = newService(true);
        UUID ownerId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        when(workflowRepository.findVisibleTo(workflowId, ownerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.startRun(ownerId, workflowId, "hi")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void startRunCreatesPendingStepsForEachDefinitionStepAndPublishesEvent() {
        WorkflowRunServiceImpl service = newService(true);
        UUID ownerId = UUID.randomUUID();
        Workflow workflow = new Workflow(UUID.randomUUID(), ownerId, "Demo", false);
        WorkflowStep step1 = new WorkflowStep(UUID.randomUUID(), workflow.getId(), (short) 1, "first", "echo", null);
        WorkflowStep step2 = new WorkflowStep(UUID.randomUUID(), workflow.getId(), (short) 2, "second", "echo", null);
        when(workflowRepository.findVisibleTo(workflow.getId(), ownerId)).thenReturn(Optional.of(workflow));
        when(workflowStepRepository.findByWorkflowIdOrderByStepOrderAsc(workflow.getId()))
                .thenReturn(List.of(step1, step2));
        when(workflowRunRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(runStepRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        RunView started = service.startRun(ownerId, workflow.getId(), "hello");

        assertThat(started.status()).isEqualTo("running");
        assertThat(started.ownerId()).isEqualTo(ownerId);
        assertThat(started.input()).isEqualTo("hello");
        assertThat(started.steps()).hasSize(2);
        assertThat(started.steps()).allSatisfy(step -> assertThat(step.status()).isEqualTo("pending"));
        verify(events).publishEvent(any(WorkflowRunStartedEvent.class));
    }

    @Test
    void getRunForeignRunIsNotFound() {
        WorkflowRunServiceImpl service = newService(true);
        UUID ownerId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        when(workflowRunRepository.findByIdAndOwnerId(runId, ownerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getRun(ownerId, runId)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void getRunReturnsTheRunWithItsSteps() {
        WorkflowRunServiceImpl service = newService(true);
        UUID ownerId = UUID.randomUUID();
        WorkflowRun run = new WorkflowRun(UUID.randomUUID(), UUID.randomUUID(), ownerId, "in", Instant.now());
        RunStep step = new RunStep(UUID.randomUUID(), run.getId(), (short) 1, "echo");
        when(workflowRunRepository.findByIdAndOwnerId(run.getId(), ownerId)).thenReturn(Optional.of(run));
        when(runStepRepository.findByRunIdOrderByStepOrderAsc(run.getId())).thenReturn(List.of(step));

        RunView view = service.getRun(ownerId, run.getId());

        assertThat(view.id()).isEqualTo(run.getId());
        assertThat(view.steps()).hasSize(1);
    }
}
