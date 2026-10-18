package com.flowcortex.workflow.infrastructure;

import com.flowcortex.workflow.domain.RunStatus;
import com.flowcortex.workflow.domain.WorkflowRun;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spring Data JPA repository for {@link WorkflowRun}. {@link #findByIdAndOwnerId} is the
 * enforcement point: runs are always owner-scoped (even for a shared workflow's run) — a
 * lookup outside the caller's id returns empty, which the application layer turns into a 404.
 */
public interface WorkflowRunRepository extends JpaRepository<WorkflowRun, UUID> {

    Optional<WorkflowRun> findByIdAndOwnerId(UUID id, UUID ownerId);

    /**
     * Running runs with no step in flight: nothing will move them unless someone advances them
     * (a pending next step whose advance was lost, or all steps completed but the run not closed).
     * Runs never swept come first, then the one swept longest ago, so a run that never moves
     * cannot keep a newer one out of the batch.
     */
    @Query(value = "SELECT r.id FROM workflow_run r WHERE r.status = 'running' AND NOT EXISTS "
            + "(SELECT 1 FROM run_step s WHERE s.run_id = r.id AND s.status = 'running') "
            + "ORDER BY r.last_swept_at NULLS FIRST, r.started_at", nativeQuery = true)
    List<UUID> findStalledRunIds(Pageable page);

    /** Stamps the runs one sweep took; {@code last_swept_at} is not mapped, so saving a run never resets it. */
    @Modifying
    @Transactional
    @Query(value = "UPDATE workflow_run SET last_swept_at = :now WHERE id IN (:ids)", nativeQuery = true)
    int markSwept(@Param("ids") Collection<UUID> ids, @Param("now") Instant now);

    /**
     * Takes the run's row lock for the rest of the caller's transaction. A step transition takes it
     * before touching its step, as a stop does with its first update, so the two never interleave:
     * otherwise each could miss the other's uncommitted change and leave the run {@code cancelling}
     * with nothing left to settle it.
     */
    @Query(value = "SELECT id FROM workflow_run WHERE id = :id FOR UPDATE", nativeQuery = true)
    List<UUID> lockById(@Param("id") UUID id);

    /** {@code running}/{@code waiting_approval} -> {@code cancelling} for the owner's run; {@code 0} otherwise. */
    @Modifying
    @Query("UPDATE WorkflowRun r SET r.status = com.flowcortex.workflow.domain.RunStatus.CANCELLING "
            + "WHERE r.id = :id AND r.ownerId = :ownerId AND r.status IN ("
            + "com.flowcortex.workflow.domain.RunStatus.RUNNING, "
            + "com.flowcortex.workflow.domain.RunStatus.WAITING_APPROVAL)")
    int requestCancel(@Param("id") UUID id, @Param("ownerId") UUID ownerId);

    /** {@code cancelling} -> {@code cancelled} once none of the run's steps is running; {@code 0} otherwise. */
    @Modifying
    @Query("UPDATE WorkflowRun r SET r.status = com.flowcortex.workflow.domain.RunStatus.CANCELLED, "
            + "r.finishedAt = :now "
            + "WHERE r.id = :id AND r.status = com.flowcortex.workflow.domain.RunStatus.CANCELLING "
            + "AND NOT EXISTS (SELECT 1 FROM RunStep s WHERE s.runId = r.id "
            + "AND s.status = com.flowcortex.workflow.domain.RunStepStatus.RUNNING)")
    int settleCancelled(@Param("id") UUID id, @Param("now") Instant now);

    /** Every owner's runs, newest first, with their workflow's name; {@code status} {@code null} matches all. */
    @Query(value = "SELECT new com.flowcortex.workflow.infrastructure.RunWithWorkflowName(r, w.name) "
            + "FROM WorkflowRun r JOIN Workflow w ON w.id = r.workflowId "
            + "WHERE (:status IS NULL OR r.status = :status) ORDER BY r.startedAt DESC, r.id DESC",
            countQuery = "SELECT count(r) FROM WorkflowRun r WHERE (:status IS NULL OR r.status = :status)")
    Page<RunWithWorkflowName> findAllNewestFirst(@Param("status") RunStatus status, Pageable page);
}
