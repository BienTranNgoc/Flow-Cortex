package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.WorkflowView;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowStep;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import com.flowcortex.workflow.infrastructure.WorkflowStepRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Task 5.4 — {@link WorkflowServiceImpl} (design.md D1, R-049). */
class WorkflowServiceImplTest {

    private final WorkflowRepository workflowRepository = mock(WorkflowRepository.class);
    private final WorkflowStepRepository workflowStepRepository = mock(WorkflowStepRepository.class);
    private final WorkflowServiceImpl service = new WorkflowServiceImpl(workflowRepository, workflowStepRepository);

    @Test
    void listVisibleWorkflowsMapsEachWithItsSteps() {
        UUID ownerId = UUID.randomUUID();
        Workflow workflow = new Workflow(UUID.randomUUID(), ownerId, "Demo", true);
        WorkflowStep step = new WorkflowStep(UUID.randomUUID(), workflow.getId(), (short) 1, "First", "echo", null);
        when(workflowRepository.findAllVisibleTo(ownerId)).thenReturn(List.of(workflow));
        when(workflowStepRepository.findByWorkflowIdOrderByStepOrderAsc(workflow.getId())).thenReturn(List.of(step));

        List<WorkflowView> views = service.listVisibleWorkflows(ownerId);

        assertThat(views).hasSize(1);
        assertThat(views.get(0).id()).isEqualTo(workflow.getId());
        assertThat(views.get(0).steps()).hasSize(1);
    }

    @Test
    void getVisibleWorkflowNotVisibleIsNotFound() {
        UUID ownerId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        when(workflowRepository.findVisibleTo(workflowId, ownerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getVisibleWorkflow(ownerId, workflowId))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void getVisibleWorkflowReturnsItWithSteps() {
        UUID ownerId = UUID.randomUUID();
        Workflow workflow = new Workflow(UUID.randomUUID(), ownerId, "Demo", false);
        when(workflowRepository.findVisibleTo(workflow.getId(), ownerId)).thenReturn(Optional.of(workflow));
        when(workflowStepRepository.findByWorkflowIdOrderByStepOrderAsc(workflow.getId())).thenReturn(List.of());

        WorkflowView view = service.getVisibleWorkflow(ownerId, workflow.getId());

        assertThat(view.name()).isEqualTo("Demo");
        assertThat(view.steps()).isEmpty();
    }

    @Test
    void createWorkflowAssignsStepOrderByListPosition() {
        UUID ownerId = UUID.randomUUID();
        when(workflowRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(workflowStepRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        WorkflowView created = service.createWorkflow(ownerId, "Two Steps", true, List.of(
                new NewWorkflowStep("first", "echo", null),
                new NewWorkflowStep("second", "echo", "{step:1.field}")));

        assertThat(created.ownerId()).isEqualTo(ownerId);
        assertThat(created.shared()).isTrue();
        assertThat(created.steps()).extracting(step -> step.stepOrder()).containsExactly(1, 2);
    }
}
