package com.flowcortex.workflow.infrastructure;

import com.flowcortex.workflow.domain.WorkflowAccessLogEntry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The share-key access log; at most one row per workflow, accessor and 10-minute window. */
public interface WorkflowAccessLogRepository extends JpaRepository<WorkflowAccessLogEntry, UUID> {

    /**
     * Writes one entry unless the workflow, accessor and window already have one, so concurrent
     * reads add a single row. Returns 1 when written, 0 when the window already held an entry.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO workflow_access_log
                (id, workflow_id, accessed_by, network, user_agent, accessor_key, window_start, created_at)
            VALUES (:id, :workflowId, CAST(:accessedBy AS uuid), CAST(:network AS varchar),
                    CAST(:userAgent AS varchar), :accessorKey, CAST(:windowStart AS timestamptz),
                    CAST(:now AS timestamptz))
            ON CONFLICT ON CONSTRAINT uq_workflow_access_log_window DO NOTHING
            """, nativeQuery = true)
    int insertOnce(@Param("id") UUID id, @Param("workflowId") UUID workflowId,
            @Param("accessedBy") UUID accessedBy, @Param("network") String network,
            @Param("userAgent") String userAgent, @Param("accessorKey") String accessorKey,
            @Param("windowStart") Instant windowStart, @Param("now") Instant now);

    @Query(value = """
            SELECT * FROM workflow_access_log WHERE workflow_id = :workflowId
             ORDER BY created_at DESC, id DESC LIMIT :limit
            """, nativeQuery = true)
    List<WorkflowAccessLogEntry> firstPage(@Param("workflowId") UUID workflowId, @Param("limit") int limit);

    /** The rows strictly after the cursor {@code (beforeTime, beforeId)} in newest-first order. */
    @Query(value = """
            SELECT * FROM workflow_access_log WHERE workflow_id = :workflowId
               AND (created_at, id) < (CAST(:beforeTime AS timestamptz), CAST(:beforeId AS uuid))
             ORDER BY created_at DESC, id DESC LIMIT :limit
            """, nativeQuery = true)
    List<WorkflowAccessLogEntry> page(@Param("workflowId") UUID workflowId, @Param("beforeTime") Instant beforeTime,
            @Param("beforeId") UUID beforeId, @Param("limit") int limit);

    long countByWorkflowIdAndCreatedAtGreaterThanEqual(UUID workflowId, Instant since);
}
