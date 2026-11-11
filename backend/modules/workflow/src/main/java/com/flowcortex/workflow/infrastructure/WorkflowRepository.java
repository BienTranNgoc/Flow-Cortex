package com.flowcortex.workflow.infrastructure;

import com.flowcortex.workflow.domain.Workflow;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for {@link Workflow}.
 *
 * <p>Visibility: a workflow is visible when it is not deleted and ({@code owner_id = caller} OR
 * ({@code is_shared = true} AND published)) — runs stay owner-scoped regardless, a shared
 * definition never implies shared runs.
 */
public interface WorkflowRepository extends JpaRepository<Workflow, UUID> {

    @Query("SELECT w FROM Workflow w WHERE w.deletedAt IS NULL "
            + "AND (w.ownerId = :ownerId OR (w.shared = true AND w.status = "
            + "com.flowcortex.workflow.domain.WorkflowStatus.PUBLISHED)) ORDER BY w.name ASC")
    List<Workflow> findAllVisibleTo(@Param("ownerId") UUID ownerId);

    @Query("SELECT w FROM Workflow w WHERE w.id = :id AND w.deletedAt IS NULL "
            + "AND (w.ownerId = :ownerId OR (w.shared = true AND w.status = "
            + "com.flowcortex.workflow.domain.WorkflowStatus.PUBLISHED))")
    Optional<Workflow> findVisibleTo(@Param("id") UUID id, @Param("ownerId") UUID ownerId);

    /** Row-locking load for the write path, so concurrent edits of one workflow serialize. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Workflow> findByIdAndDeletedAtIsNull(UUID id);

    /** Non-locking read of a live workflow, for export. */
    @Query("SELECT w FROM Workflow w WHERE w.id = :id AND w.deletedAt IS NULL")
    Optional<Workflow> findActiveById(@Param("id") UUID id);

    /** Atomic counter bump, so concurrent forks never lose an increment. */
    @Modifying
    @Query("UPDATE Workflow w SET w.forkCount = w.forkCount + 1 WHERE w.id = :id")
    int incrementForkCount(@Param("id") UUID id);
}
