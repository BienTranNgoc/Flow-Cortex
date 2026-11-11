package com.flowcortex.workflow.infrastructure;

import com.flowcortex.workflow.domain.WorkflowDataEntry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data JPA repository for {@link WorkflowDataEntry}. */
public interface WorkflowDataEntryRepository extends JpaRepository<WorkflowDataEntry, UUID> {

    Optional<WorkflowDataEntry> findByWorkflowIdAndKey(UUID workflowId, String key);

    List<WorkflowDataEntry> findByWorkflowIdOrderByUpdatedAtDesc(UUID workflowId);

    long countByWorkflowId(UUID workflowId);

    /** Removes the entry; returns 0 when there was none. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    int deleteByWorkflowIdAndKey(UUID workflowId, String key);

    /** The {@code template}-typed entry of the run's own workflow; an entry of any other type is never returned. */
    @Query("SELECT e FROM WorkflowDataEntry e, WorkflowRun r "
            + "WHERE r.id = :runId AND e.workflowId = r.workflowId AND e.key = :key AND e.dataType = 'template'")
    Optional<WorkflowDataEntry> findTemplateForRun(@Param("runId") UUID runId, @Param("key") String key);
}
