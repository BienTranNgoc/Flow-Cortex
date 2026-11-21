package com.flowcortex.workflow.infrastructure;

import com.flowcortex.workflow.domain.WorkflowVisibilityAuditEntry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The visibility change history of a workflow. */
public interface WorkflowVisibilityAuditRepository extends JpaRepository<WorkflowVisibilityAuditEntry, UUID> {

    @Query(value = """
            SELECT * FROM workflow_visibility_audit WHERE workflow_id = :workflowId
             ORDER BY created_at DESC, id DESC LIMIT :limit
            """, nativeQuery = true)
    List<WorkflowVisibilityAuditEntry> firstPage(@Param("workflowId") UUID workflowId, @Param("limit") int limit);

    /** The rows strictly after the cursor {@code (beforeTime, beforeId)} in newest-first order. */
    @Query(value = """
            SELECT * FROM workflow_visibility_audit WHERE workflow_id = :workflowId
               AND (created_at, id) < (CAST(:beforeTime AS timestamptz), CAST(:beforeId AS uuid))
             ORDER BY created_at DESC, id DESC LIMIT :limit
            """, nativeQuery = true)
    List<WorkflowVisibilityAuditEntry> page(@Param("workflowId") UUID workflowId,
            @Param("beforeTime") Instant beforeTime, @Param("beforeId") UUID beforeId, @Param("limit") int limit);
}
