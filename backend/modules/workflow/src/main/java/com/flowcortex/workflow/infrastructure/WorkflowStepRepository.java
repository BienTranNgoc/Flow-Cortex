package com.flowcortex.workflow.infrastructure;

import com.flowcortex.workflow.domain.WorkflowStep;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data JPA repository for {@link WorkflowStep}. */
public interface WorkflowStepRepository extends JpaRepository<WorkflowStep, UUID> {

    List<WorkflowStep> findByWorkflowIdOrderByStepOrderAsc(UUID workflowId);

    /** The highest {@code step_order} of the workflow, 0 when it has no step. */
    @Query("SELECT COALESCE(MAX(CAST(s.stepOrder AS integer)), 0) FROM WorkflowStep s WHERE s.workflowId = :workflowId")
    int findMaxStepOrder(@Param("workflowId") UUID workflowId);

    /**
     * Negates the order of the given steps so their final orders can be written without meeting the unique
     * constraint; every parked row must be given its order back by {@link #setOrder}.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE workflow_step SET step_order = -step_order "
            + "WHERE workflow_id = :workflowId AND id IN (:ids)", nativeQuery = true)
    int parkOrders(@Param("workflowId") UUID workflowId, @Param("ids") List<UUID> ids);

    /** Writes {@code step_order}, the one column the entity cannot update. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE workflow_step SET step_order = :order WHERE id = :id", nativeQuery = true)
    int setOrder(@Param("id") UUID id, @Param("order") short order);
}
