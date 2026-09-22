package com.flowcortex.workflow.infrastructure;

import com.flowcortex.workflow.domain.WorkflowStep;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data JPA repository for {@link WorkflowStep} (design.md D1, task 5.2). */
public interface WorkflowStepRepository extends JpaRepository<WorkflowStep, UUID> {

    List<WorkflowStep> findByWorkflowIdOrderByStepOrderAsc(UUID workflowId);
}
