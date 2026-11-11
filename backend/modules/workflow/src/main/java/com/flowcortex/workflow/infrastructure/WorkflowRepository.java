package com.flowcortex.workflow.infrastructure;

import com.flowcortex.workflow.domain.Workflow;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for {@link Workflow}.
 *
 * <p>Visibility: a workflow is readable when it is not deleted and its owner is the caller, or it
 * is published and either public or unlisted with a link grant for the caller. The list holds
 * only the caller's own and published public workflows: a grant never puts a workflow in a list.
 * Runs stay owner-scoped regardless.
 */
public interface WorkflowRepository extends JpaRepository<Workflow, UUID> {

    @Query("SELECT w FROM Workflow w WHERE w.deletedAt IS NULL "
            + "AND (w.ownerId = :ownerId OR (w.status = "
            + "com.flowcortex.workflow.domain.WorkflowStatus.PUBLISHED AND w.visibility = "
            + "com.flowcortex.workflow.api.WorkflowVisibility.PUBLIC)) ORDER BY w.name ASC")
    List<Workflow> findAllVisibleTo(@Param("ownerId") UUID ownerId);

    @Query("SELECT w FROM Workflow w WHERE w.id = :id AND w.deletedAt IS NULL "
            + "AND (w.ownerId = :ownerId OR (w.status = "
            + "com.flowcortex.workflow.domain.WorkflowStatus.PUBLISHED AND "
            + "(w.visibility = com.flowcortex.workflow.api.WorkflowVisibility.PUBLIC "
            + "OR (w.visibility = com.flowcortex.workflow.api.WorkflowVisibility.UNLISTED "
            + "AND EXISTS (SELECT 1 FROM WorkflowLinkGrant g "
            + "WHERE g.workflowId = w.id AND g.userId = :ownerId)))))")
    Optional<Workflow> findVisibleTo(@Param("id") UUID id, @Param("ownerId") UUID ownerId);

    Optional<Workflow> findByUnlistedKey(String unlistedKey);

    /** Row-locking load for the write path, so concurrent edits of one workflow serialize. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Workflow> findByIdAndDeletedAtIsNull(UUID id);

    /** The same load without the row lock, for owner-only reads. */
    @Query("SELECT w FROM Workflow w WHERE w.id = :id AND w.deletedAt IS NULL")
    Optional<Workflow> findActiveById(@Param("id") UUID id);
}
