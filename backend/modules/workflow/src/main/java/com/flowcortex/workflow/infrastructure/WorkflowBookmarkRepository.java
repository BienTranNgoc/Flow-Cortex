package com.flowcortex.workflow.infrastructure;

import com.flowcortex.workflow.domain.WorkflowBookmark;
import com.flowcortex.workflow.domain.WorkflowBookmarkId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Saved workflows. The paged list and its count restrict to the workflows the caller may read, with
 * the same predicate as {@code WorkflowRepository.findVisibleTo}, its link-grant branch included:
 * a saved workflow that left the caller's sight is hidden and not counted, but its row stays.
 */
public interface WorkflowBookmarkRepository extends JpaRepository<WorkflowBookmark, WorkflowBookmarkId> {

    String VISIBLE_TO_CALLER = "w.deletedAt IS NULL "
            + "AND (w.ownerId = :caller OR (w.status = "
            + "com.flowcortex.workflow.domain.WorkflowStatus.PUBLISHED AND "
            + "(w.visibility = com.flowcortex.workflow.api.WorkflowVisibility.PUBLIC "
            + "OR (w.visibility = com.flowcortex.workflow.api.WorkflowVisibility.UNLISTED "
            + "AND EXISTS (SELECT 1 FROM WorkflowLinkGrant g "
            + "WHERE g.workflowId = w.id AND g.userId = :caller)))))";

    /** Saves the workflow for the member; returns 1 when a row was written, 0 when it was already saved. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO workflow_bookmark (user_id, workflow_id, created_at)
            VALUES (:userId, :workflowId, CAST(:now AS timestamptz))
            ON CONFLICT (user_id, workflow_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("userId") UUID userId, @Param("workflowId") UUID workflowId,
            @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM WorkflowBookmark b WHERE b.userId = :userId AND b.workflowId = :workflowId")
    int deleteByUserIdAndWorkflowId(@Param("userId") UUID userId, @Param("workflowId") UUID workflowId);

    boolean existsByUserIdAndWorkflowId(UUID userId, UUID workflowId);

    /** The caller's visible saved workflows, newest saved first. */
    @Query("SELECT new com.flowcortex.workflow.infrastructure.BookmarkedWorkflowRow(w, b.createdAt) "
            + "FROM WorkflowBookmark b JOIN Workflow w ON w.id = b.workflowId "
            + "WHERE b.userId = :caller AND " + VISIBLE_TO_CALLER
            + " ORDER BY b.createdAt DESC, b.workflowId DESC")
    List<BookmarkedWorkflowRow> pageVisible(@Param("caller") UUID caller, Pageable page);

    @Query("SELECT count(b) FROM WorkflowBookmark b JOIN Workflow w ON w.id = b.workflowId "
            + "WHERE b.userId = :caller AND " + VISIBLE_TO_CALLER)
    long countVisible(@Param("caller") UUID caller);
}
