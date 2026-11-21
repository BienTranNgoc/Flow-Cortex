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

    /** The one JPQL predicate that decides which runs are member results; every result query uses it. */
    String MEMBER_RESULT_RUN = "r.status = com.flowcortex.workflow.domain.RunStatus.COMPLETED";

    /** The owner's run; a soft-deleted run is not found, so every owner-scoped caller answers 404 for it. */
    @Query("SELECT r FROM WorkflowRun r WHERE r.id = :id AND r.ownerId = :ownerId AND r.deletedAt IS NULL")
    Optional<WorkflowRun> findByIdAndOwnerId(@Param("id") UUID id, @Param("ownerId") UUID ownerId);

    /**
     * Stamps the owner's finished run as deleted; {@code 0} when it is another owner's, already deleted,
     * missing or still live. The caller holds {@link #lockById} so the status cannot change under the write.
     */
    @Modifying
    @Query(value = "UPDATE workflow_run SET deleted_at = :now WHERE id = :id AND owner_id = :ownerId "
            + "AND deleted_at IS NULL AND status IN ('completed', 'error', 'cancelled')", nativeQuery = true)
    int softDelete(@Param("id") UUID id, @Param("ownerId") UUID ownerId, @Param("now") Instant now);

    /**
     * The owner's live runs, newest start first, with each workflow's name. Every filter that is
     * {@code null} (or {@code favoriteOnly} false) matches all; {@code startedFrom} is inclusive,
     * {@code startedBefore} exclusive.
     */
    @Query(value = "SELECT new com.flowcortex.workflow.infrastructure.RunHistoryRow(r, w.name) "
            + "FROM WorkflowRun r JOIN Workflow w ON w.id = r.workflowId "
            + "WHERE r.ownerId = :ownerId AND r.deletedAt IS NULL "
            + "AND (:status IS NULL OR r.status = :status) "
            + "AND (:workflowId IS NULL OR r.workflowId = :workflowId) "
            + "AND (cast(:startedFrom as Instant) IS NULL OR r.startedAt >= :startedFrom) "
            + "AND (cast(:startedBefore as Instant) IS NULL OR r.startedAt < :startedBefore) "
            + "AND (:favoriteOnly = false OR r.favorite = true) "
            + "ORDER BY r.startedAt DESC, r.id DESC",
            countQuery = "SELECT count(r) FROM WorkflowRun r "
                    + "WHERE r.ownerId = :ownerId AND r.deletedAt IS NULL "
                    + "AND (:status IS NULL OR r.status = :status) "
                    + "AND (:workflowId IS NULL OR r.workflowId = :workflowId) "
                    + "AND (cast(:startedFrom as Instant) IS NULL OR r.startedAt >= :startedFrom) "
                    + "AND (cast(:startedBefore as Instant) IS NULL OR r.startedAt < :startedBefore) "
                    + "AND (:favoriteOnly = false OR r.favorite = true)")
    Page<RunHistoryRow> findHistory(@Param("ownerId") UUID ownerId, @Param("status") RunStatus status,
            @Param("workflowId") UUID workflowId, @Param("startedFrom") Instant startedFrom,
            @Param("startedBefore") Instant startedBefore, @Param("favoriteOnly") boolean favoriteOnly,
            Pageable page);

    /** Every workflow the owner has a live run of, once each, by name; a deleted workflow is kept. */
    @Query("SELECT DISTINCT new com.flowcortex.workflow.infrastructure.WorkflowChoice(w.id, w.name) "
            + "FROM WorkflowRun r JOIN Workflow w ON w.id = r.workflowId "
            + "WHERE r.ownerId = :ownerId AND r.deletedAt IS NULL ORDER BY w.name, w.id")
    List<WorkflowChoice> findHistoryWorkflows(@Param("ownerId") UUID ownerId, Pageable page);

    /** The owner's live runs that have not finished, newest start first (the tray's active bucket). */
    @Query("SELECT new com.flowcortex.workflow.infrastructure.RunHistoryRow(r, w.name) "
            + "FROM WorkflowRun r JOIN Workflow w ON w.id = r.workflowId "
            + "WHERE r.ownerId = :ownerId AND r.deletedAt IS NULL AND r.status IN ("
            + "com.flowcortex.workflow.domain.RunStatus.RUNNING, "
            + "com.flowcortex.workflow.domain.RunStatus.WAITING_APPROVAL, "
            + "com.flowcortex.workflow.domain.RunStatus.CANCELLING) "
            + "ORDER BY r.startedAt DESC, r.id DESC")
    List<RunHistoryRow> findTrayActive(@Param("ownerId") UUID ownerId, Pageable page);

    /** The owner's runs that finished at or after {@code finishedSince}, most recently finished first. */
    @Query("SELECT new com.flowcortex.workflow.infrastructure.RunHistoryRow(r, w.name) "
            + "FROM WorkflowRun r JOIN Workflow w ON w.id = r.workflowId "
            + "WHERE r.ownerId = :ownerId AND r.deletedAt IS NULL AND r.status IN ("
            + "com.flowcortex.workflow.domain.RunStatus.COMPLETED, "
            + "com.flowcortex.workflow.domain.RunStatus.ERROR, "
            + "com.flowcortex.workflow.domain.RunStatus.CANCELLED) "
            + "AND r.finishedAt >= :finishedSince ORDER BY r.finishedAt DESC, r.id DESC")
    List<RunHistoryRow> findTrayRecent(@Param("ownerId") UUID ownerId, @Param("finishedSince") Instant finishedSince,
            Pageable page);

    /** The owner's runs started at or after {@code since}, deleted ones included: their credits were spent. */
    @Query("SELECT new com.flowcortex.workflow.infrastructure.RunUsageRow(r.id, r.workflowId, w.name, r.status, "
            + "r.startedAt) FROM WorkflowRun r JOIN Workflow w ON w.id = r.workflowId "
            + "WHERE r.ownerId = :ownerId AND r.startedAt >= :since ORDER BY r.startedAt DESC, r.id DESC")
    List<RunUsageRow> findUsageRows(@Param("ownerId") UUID ownerId, @Param("since") Instant since);

    boolean existsByOwnerIdAndStatus(UUID ownerId, RunStatus status);

    /** A member-result run with its workflow's name; empty for any other run. */
    @Query("SELECT new com.flowcortex.workflow.infrastructure.RunWithWorkflowName(r, w.name) "
            + "FROM WorkflowRun r JOIN Workflow w ON w.id = r.workflowId "
            + "WHERE r.id = :id AND " + MEMBER_RESULT_RUN)
    Optional<RunWithWorkflowName> findCompletedWithWorkflowName(@Param("id") UUID id);

    /** The owner's member-result runs, most recently finished first. */
    @Query("SELECT new com.flowcortex.workflow.infrastructure.RunWithWorkflowName(r, w.name) "
            + "FROM WorkflowRun r JOIN Workflow w ON w.id = r.workflowId "
            + "WHERE r.ownerId = :ownerId AND " + MEMBER_RESULT_RUN + " ORDER BY r.finishedAt DESC, r.id DESC")
    List<RunWithWorkflowName> findCompletedByOwnerNewestFirst(@Param("ownerId") UUID ownerId, Pageable page);

    /** Ids of member-result runs strictly after {@code after} in id order. */
    @Query("SELECT r.id FROM WorkflowRun r WHERE r.id > :after AND " + MEMBER_RESULT_RUN + " ORDER BY r.id")
    List<UUID> findCompletedIdsAfter(@Param("after") UUID after, Pageable page);

    Optional<WorkflowRun> findByOwnerIdAndRequestKey(UUID ownerId, String requestKey);

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

    /** {@code error} -> {@code running} for the owner's run, clearing its failure; {@code 0} otherwise. */
    @Modifying
    @Query("UPDATE WorkflowRun r SET r.status = com.flowcortex.workflow.domain.RunStatus.RUNNING, "
            + "r.errorMessage = NULL, r.finishedAt = NULL "
            + "WHERE r.id = :id AND r.ownerId = :ownerId "
            + "AND r.status = com.flowcortex.workflow.domain.RunStatus.ERROR")
    int reopen(@Param("id") UUID id, @Param("ownerId") UUID ownerId);

    /** {@code cancelling} -> {@code cancelled} once none of the run's steps is running; {@code 0} otherwise. */
    @Modifying
    @Query("UPDATE WorkflowRun r SET r.status = com.flowcortex.workflow.domain.RunStatus.CANCELLED, "
            + "r.finishedAt = :now "
            + "WHERE r.id = :id AND r.status = com.flowcortex.workflow.domain.RunStatus.CANCELLING "
            + "AND NOT EXISTS (SELECT 1 FROM RunStep s WHERE s.runId = r.id "
            + "AND s.status = com.flowcortex.workflow.domain.RunStepStatus.RUNNING)")
    int settleCancelled(@Param("id") UUID id, @Param("now") Instant now);

    /**
     * Every owner's runs, newest first, with their workflow's name. A {@code null} {@code status} or
     * {@code workflowId} matches all. {@code from} (inclusive) and {@code to} (exclusive) are never
     * {@code null}: PostgreSQL cannot type a null timestamp parameter in {@code :x IS NULL}, so an open
     * end is passed as a bound outside any real start time.
     */
    @Query(value = "SELECT new com.flowcortex.workflow.infrastructure.RunWithWorkflowName(r, w.name) "
            + "FROM WorkflowRun r JOIN Workflow w ON w.id = r.workflowId "
            + "WHERE (:status IS NULL OR r.status = :status) "
            + "AND (:workflowId IS NULL OR r.workflowId = :workflowId) "
            + "AND r.startedAt >= :from AND r.startedAt < :to ORDER BY r.startedAt DESC, r.id DESC",
            countQuery = "SELECT count(r) FROM WorkflowRun r WHERE (:status IS NULL OR r.status = :status) "
                    + "AND (:workflowId IS NULL OR r.workflowId = :workflowId) "
                    + "AND r.startedAt >= :from AND r.startedAt < :to")
    Page<RunWithWorkflowName> findAllNewestFirst(@Param("status") RunStatus status,
            @Param("workflowId") UUID workflowId, @Param("from") Instant from, @Param("to") Instant to,
            Pageable page);

    /** A child run still in flight, with the owner its own stop must be issued under. */
    record ChildRef(UUID id, UUID ownerId) {
    }

    /** Runs started by any step of {@code parentRunId} that have not reached a final status. */
    @Query("SELECT new com.flowcortex.workflow.infrastructure.WorkflowRunRepository$ChildRef(r.id, r.ownerId) "
            + "FROM WorkflowRun r WHERE r.parentRunId = :parentRunId AND r.status IN ("
            + "com.flowcortex.workflow.domain.RunStatus.RUNNING, "
            + "com.flowcortex.workflow.domain.RunStatus.WAITING_APPROVAL, "
            + "com.flowcortex.workflow.domain.RunStatus.CANCELLING)")
    List<ChildRef> findRunningChildren(@Param("parentRunId") UUID parentRunId);

    /** Runs started by one step of its parent that have not reached a final status. */
    @Query("SELECT new com.flowcortex.workflow.infrastructure.WorkflowRunRepository$ChildRef(r.id, r.ownerId) "
            + "FROM WorkflowRun r WHERE r.parentStepId = :parentStepId AND r.status IN ("
            + "com.flowcortex.workflow.domain.RunStatus.RUNNING, "
            + "com.flowcortex.workflow.domain.RunStatus.WAITING_APPROVAL, "
            + "com.flowcortex.workflow.domain.RunStatus.CANCELLING)")
    List<ChildRef> findRunningChildrenOfStep(@Param("parentStepId") UUID parentStepId);

    /** The step that started this run, or empty for a run no step started. */
    @Query("SELECT r.parentStepId FROM WorkflowRun r WHERE r.id = :runId AND r.parentStepId IS NOT NULL")
    Optional<UUID> findParentStepIdById(@Param("runId") UUID runId);
}
