package com.flowcortex.workflow.infrastructure;

import com.flowcortex.workflow.domain.WorkflowStep;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data JPA repository for {@link WorkflowStep}. */
public interface WorkflowStepRepository extends JpaRepository<WorkflowStep, UUID> {

    List<WorkflowStep> findByWorkflowIdOrderByStepOrderAsc(UUID workflowId);

    /** The highest {@code step_order} of the workflow, 0 when it has no step. */
    @Query("SELECT COALESCE(MAX(CAST(s.stepOrder AS integer)), 0) FROM WorkflowStep s WHERE s.workflowId = :workflowId")
    int findMaxStepOrder(@Param("workflowId") UUID workflowId);
}
