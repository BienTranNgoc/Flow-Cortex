package com.flowcortex.workflow.infrastructure;

import com.flowcortex.workflow.domain.WorkflowLinkGrant;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Link grants: who may read an unlisted workflow without presenting its key again. */
public interface WorkflowLinkGrantRepository extends JpaRepository<WorkflowLinkGrant, WorkflowLinkGrant.Key> {

    /**
     * Writes the grant only while {@code key} is the workflow's current, unexpired key of a
     * published unlisted workflow. The share lock waits on a concurrent visibility change or
     * rotation and re-checks the predicate on the committed row, so a change that commits first
     * leaves no grant. Returns 1 when a row was written, 0 when none matched or the grant exists.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO workflow_link_grant (workflow_id, user_id, created_at)
            SELECT w.id, CAST(:userId AS uuid), CAST(:now AS timestamptz) FROM workflow w
             WHERE w.id = :workflowId AND w.unlisted_key = :key AND w.visibility = 'unlisted'
               AND w.status = 'published' AND w.deleted_at IS NULL
               AND (w.unlisted_key_expires_at IS NULL OR w.unlisted_key_expires_at > CAST(:now AS timestamptz))
               FOR SHARE OF w
            ON CONFLICT (workflow_id, user_id) DO NOTHING
            """, nativeQuery = true)
    int admit(@Param("workflowId") UUID workflowId, @Param("userId") UUID userId, @Param("key") String key,
            @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM WorkflowLinkGrant g WHERE g.workflowId = :workflowId AND g.userId = :userId")
    int deleteByWorkflowIdAndUserId(@Param("workflowId") UUID workflowId, @Param("userId") UUID userId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM WorkflowLinkGrant g WHERE g.workflowId = :workflowId")
    int deleteByWorkflowId(@Param("workflowId") UUID workflowId);

    @Query(value = """
            SELECT * FROM workflow_link_grant WHERE workflow_id = :workflowId
             ORDER BY created_at DESC, user_id DESC LIMIT :limit
            """, nativeQuery = true)
    List<WorkflowLinkGrant> firstPage(@Param("workflowId") UUID workflowId, @Param("limit") int limit);

    /** The rows strictly after the cursor {@code (beforeTime, beforeUserId)} in newest-first order. */
    @Query(value = """
            SELECT * FROM workflow_link_grant WHERE workflow_id = :workflowId
               AND (created_at, user_id) < (CAST(:beforeTime AS timestamptz), CAST(:beforeUserId AS uuid))
             ORDER BY created_at DESC, user_id DESC LIMIT :limit
            """, nativeQuery = true)
    List<WorkflowLinkGrant> page(@Param("workflowId") UUID workflowId, @Param("beforeTime") Instant beforeTime,
            @Param("beforeUserId") UUID beforeUserId, @Param("limit") int limit);
}
