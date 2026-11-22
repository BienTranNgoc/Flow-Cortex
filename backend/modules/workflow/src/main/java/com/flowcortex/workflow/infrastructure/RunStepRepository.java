package com.flowcortex.workflow.infrastructure;

import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.domain.RunStepStatus;
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

/**
 * Spring Data JPA repository for {@link RunStep}.
 *
 * <p>Every transition is one atomic {@code UPDATE ... WHERE}, decided by row count, not
 * read-then-check-then-write (which would race): {@link #claimPending} guards single-active
 * dispatch ({@code status = 'pending'}); every later transition requires {@code status =
 * 'running'} and the step's {@code attempt}, so a late or duplicate result, or a heartbeat for a
 * step the reaper already closed, changes nothing and gets {@code 0} back.
 */
public interface RunStepRepository extends JpaRepository<RunStep, UUID> {

    /**
     * The run's top-level steps in order; the children of an iterate step are excluded, so every
     * reader that means "the run's steps" keeps seeing one row per step.
     */
    @Query("SELECT r FROM RunStep r WHERE r.runId = :runId AND r.parentStepId IS NULL ORDER BY r.stepOrder ASC")
    List<RunStep> findByRunIdOrderByStepOrderAsc(@Param("runId") UUID runId);

    /**
     * The items of every iterate step of the run in the attempt their parent is in now, by parent
     * order and then item index.
     */
    @Query("SELECT c FROM RunStep c JOIN RunStep p ON p.id = c.parentStepId "
            + "WHERE c.runId = :runId AND c.parentAttempt = p.attempt ORDER BY p.stepOrder ASC, c.itemIndex ASC")
    List<RunStep> findCurrentChildren(@Param("runId") UUID runId);

    /** One item of an iterate step as the progress decision reads it. */
    interface ChildRow {

        UUID getId();

        short getItemIndex();

        RunStepStatus getStatus();

        String getOutput();

        String getOutputs();

        String getErrorCode();

        String getErrorMessage();
    }

    /**
     * The items of one parent attempt in item order, as columns and not entities: a caller whose
     * child was moved by another transaction would otherwise read it back at its earlier status
     * from its own persistence context.
     */
    @Query("SELECT c.id AS id, c.itemIndex AS itemIndex, c.status AS status, c.output AS output, "
            + "c.outputs AS outputs, c.errorCode AS errorCode, c.errorMessage AS errorMessage "
            + "FROM RunStep c WHERE c.parentStepId = :parentId AND c.parentAttempt = :parentAttempt "
            + "ORDER BY c.itemIndex ASC")
    List<ChildRow> findChildStates(@Param("parentId") UUID parentId, @Param("parentAttempt") short parentAttempt);

    /**
     * Per item index, the completed item of the highest parent attempt below {@code beforeAttempt},
     * in item order: what a retried iterate step may carry over.
     */
    @Query("SELECT c FROM RunStep c WHERE c.parentStepId = :parentId "
            + "AND c.status = com.flowcortex.workflow.domain.RunStepStatus.COMPLETED "
            + "AND c.parentAttempt = (SELECT max(d.parentAttempt) FROM RunStep d WHERE d.parentStepId = c.parentStepId "
            + "AND d.itemIndex = c.itemIndex AND d.parentAttempt < :beforeAttempt "
            + "AND d.status = com.flowcortex.workflow.domain.RunStepStatus.COMPLETED) "
            + "ORDER BY c.itemIndex ASC")
    List<RunStep> findLatestCompletedChildren(@Param("parentId") UUID parentId,
            @Param("beforeAttempt") short beforeAttempt);

    /** The items of one parent attempt in item order. */
    @Query("SELECT c FROM RunStep c WHERE c.parentStepId = :parentId AND c.parentAttempt = :parentAttempt "
            + "ORDER BY c.itemIndex ASC")
    List<RunStep> findChildren(@Param("parentId") UUID parentId, @Param("parentAttempt") short parentAttempt);

    @Query("SELECT count(c) FROM RunStep c WHERE c.parentStepId = :parentId AND c.parentAttempt = :parentAttempt "
            + "AND c.status = com.flowcortex.workflow.domain.RunStepStatus.RUNNING")
    long countRunningChildren(@Param("parentId") UUID parentId, @Param("parentAttempt") short parentAttempt);

    /** Running items of the iterate step in any of its attempts: an earlier attempt's items outlive its failure. */
    @Query("SELECT count(c) FROM RunStep c WHERE c.parentStepId = :parentId "
            + "AND c.status = com.flowcortex.workflow.domain.RunStepStatus.RUNNING")
    long countRunningChildrenOfAnyAttempt(@Param("parentId") UUID parentId);

    /** Items of one parent attempt that are running or still waiting to start. */
    @Query("SELECT count(c) FROM RunStep c WHERE c.parentStepId = :parentId AND c.parentAttempt = :parentAttempt "
            + "AND c.status IN (com.flowcortex.workflow.domain.RunStepStatus.RUNNING, "
            + "com.flowcortex.workflow.domain.RunStepStatus.PENDING)")
    long countActiveChildren(@Param("parentId") UUID parentId, @Param("parentAttempt") short parentAttempt);

    /** A running iterate step with no running item in its current attempt. */
    interface IdleParent {

        UUID getId();

        short getAttempt();
    }

    /**
     * Running top-level iterate steps of the run with no running item in their current attempt: nothing
     * will move them unless someone advances the run. Columns, not entities, so a caller that fails them
     * and then reads the run's steps in the same transaction does not get them back as running.
     */
    @Query("SELECT p.id AS id, p.attempt AS attempt FROM RunStep p "
            + "WHERE p.runId = :runId AND p.parentStepId IS NULL AND p.type = 'iterate' "
            + "AND p.status = com.flowcortex.workflow.domain.RunStepStatus.RUNNING "
            + "AND NOT EXISTS (SELECT 1 FROM RunStep c WHERE c.parentStepId = p.id AND c.parentAttempt = p.attempt "
            + "AND c.status = com.flowcortex.workflow.domain.RunStepStatus.RUNNING) "
            + "ORDER BY p.stepOrder ASC")
    List<IdleParent> findIdleIterateParents(@Param("runId") UUID runId);

    /** What a step is: its type and, for an item of an iterate step, which parent attempt and index. */
    interface Lineage {

        String getType();

        UUID getParentStepId();

        Short getParentAttempt();

        Short getItemIndex();
    }

    /** Columns, not the entity, for the reason {@link #findTimingById} gives. */
    @Query("SELECT r.type AS type, r.parentStepId AS parentStepId, r.parentAttempt AS parentAttempt, "
            + "r.itemIndex AS itemIndex FROM RunStep r WHERE r.id = :id")
    Optional<Lineage> findLineageById(@Param("id") UUID id);

    /** A step's status and result as the database holds them now. */
    interface StepResult {

        RunStepStatus getStatus();

        short getAttempt();

        String getOutput();

        String getOutputs();
    }

    @Query("SELECT r.status AS status, r.attempt AS attempt, r.output AS output, r.outputs AS outputs "
            + "FROM RunStep r WHERE r.id = :id")
    Optional<StepResult> findResultById(@Param("id") UUID id);

    /** A step's type, order, stored input and pickup time. */
    interface Timing {

        String getType();

        short getStepOrder();

        String getInput();

        Instant getPickedUpAt();
    }

    /**
     * Columns, not the entity: a caller that transitions the step in another transaction and then
     * reads the run's steps in its own would otherwise get this step back as it was before.
     */
    @Query("SELECT r.type AS type, r.stepOrder AS stepOrder, r.input AS input, r.pickedUpAt AS pickedUpAt "
            + "FROM RunStep r WHERE r.id = :id")
    Optional<Timing> findTimingById(@Param("id") UUID id);

    /** Top-level steps of the run that are not {@code completed}; the items of an iterate step do not count. */
    @Query("SELECT count(r) FROM RunStep r WHERE r.runId = :runId AND r.parentStepId IS NULL "
            + "AND r.status <> com.flowcortex.workflow.domain.RunStepStatus.COMPLETED")
    long countTopLevelNotCompleted(@Param("runId") UUID runId);

    /** {@code pending -> running}, only while the step's run is {@code running}: no step starts after a stop. */
    @Modifying
    @Query("UPDATE RunStep r SET r.status = com.flowcortex.workflow.domain.RunStepStatus.RUNNING, r.startedAt = :now, "
            + "r.queueDeadline = :queueDeadline, r.input = :input "
            + "WHERE r.id = :id AND r.status = com.flowcortex.workflow.domain.RunStepStatus.PENDING "
            + "AND EXISTS (SELECT 1 FROM WorkflowRun w WHERE w.id = r.runId "
            + "AND w.status = com.flowcortex.workflow.domain.RunStatus.RUNNING)")
    int claimPending(@Param("id") UUID id, @Param("now") Instant now, @Param("queueDeadline") Instant queueDeadline,
            @Param("input") String input);

    /**
     * {@code error -> pending} as the next attempt of the step: every trace of the failed attempt is
     * cleared and the stored input is set back to {@code input}, because a claim overwrites it with the
     * resolved one. The attempt guard decides between two callers re-arming the same failure.
     */
    @Modifying
    @Query("UPDATE RunStep r SET r.status = com.flowcortex.workflow.domain.RunStepStatus.PENDING, "
            + "r.attempt = r.attempt + 1, r.output = NULL, r.outputs = NULL, r.errorCode = NULL, "
            + "r.errorMessage = NULL, r.startedAt = NULL, r.finishedAt = NULL, r.pickedUpAt = NULL, "
            + "r.leaseUntil = NULL, r.queueDeadline = NULL, r.runDeadline = NULL, r.progressPercent = NULL, "
            + "r.deliveryCount = 0, r.input = :input "
            + "WHERE r.id = :id AND r.runId = :runId AND r.attempt = :attempt AND r.attempt < 32767 "
            + "AND r.status = com.flowcortex.workflow.domain.RunStepStatus.ERROR")
    int reArm(@Param("id") UUID id, @Param("runId") UUID runId, @Param("attempt") short attempt,
            @Param("input") String input);

    /** Every step of the run that has not started becomes {@code skipped}; returns how many. */
    @Modifying
    @Query("UPDATE RunStep r SET r.status = com.flowcortex.workflow.domain.RunStepStatus.SKIPPED "
            + "WHERE r.runId = :runId AND r.status = com.flowcortex.workflow.domain.RunStepStatus.PENDING")
    int skipPending(@Param("runId") UUID runId);

    /**
     * {@code pending -> running} for one item, while its run and its parent are {@code running} in the
     * attempt the item belongs to and fewer than {@code limit} items of that attempt are running. The
     * count sits in the same statement, so a caller that took the run's lock first sees every earlier
     * claim of the run committed and two claims cannot both take the last slot.
     */
    @Modifying
    @Query("UPDATE RunStep r SET r.status = com.flowcortex.workflow.domain.RunStepStatus.RUNNING, r.startedAt = :now, "
            + "r.queueDeadline = :queueDeadline, r.input = :input "
            + "WHERE r.id = :id AND r.status = com.flowcortex.workflow.domain.RunStepStatus.PENDING "
            + "AND r.parentStepId = :parentId AND r.parentAttempt = :parentAttempt "
            + "AND EXISTS (SELECT 1 FROM WorkflowRun w WHERE w.id = r.runId "
            + "AND w.status = com.flowcortex.workflow.domain.RunStatus.RUNNING) "
            + "AND EXISTS (SELECT 1 FROM RunStep p WHERE p.id = :parentId AND p.attempt = :parentAttempt "
            + "AND p.status = com.flowcortex.workflow.domain.RunStepStatus.RUNNING) "
            + "AND (SELECT count(c) FROM RunStep c WHERE c.parentStepId = :parentId "
            + "AND c.parentAttempt = :parentAttempt "
            + "AND c.status = com.flowcortex.workflow.domain.RunStepStatus.RUNNING) < :limit")
    int claimPendingChild(@Param("id") UUID id, @Param("parentId") UUID parentId,
            @Param("parentAttempt") short parentAttempt, @Param("limit") int limit, @Param("now") Instant now,
            @Param("queueDeadline") Instant queueDeadline, @Param("input") String input);

    /**
     * {@code pending -> running} for a regenerated item: the run and its parent are {@code completed}
     * and the same running-items count as {@link #claimPendingChild} stays below {@code limit}.
     */
    @Modifying
    @Query("UPDATE RunStep r SET r.status = com.flowcortex.workflow.domain.RunStepStatus.RUNNING, r.startedAt = :now, "
            + "r.queueDeadline = :queueDeadline, r.input = :input "
            + "WHERE r.id = :id AND r.status = com.flowcortex.workflow.domain.RunStepStatus.PENDING "
            + "AND r.parentStepId = :parentId AND r.parentAttempt = :parentAttempt "
            + "AND EXISTS (SELECT 1 FROM WorkflowRun w WHERE w.id = r.runId "
            + "AND w.status = com.flowcortex.workflow.domain.RunStatus.COMPLETED) "
            + "AND EXISTS (SELECT 1 FROM RunStep p WHERE p.id = :parentId AND p.attempt = :parentAttempt "
            + "AND p.status = com.flowcortex.workflow.domain.RunStepStatus.COMPLETED) "
            + "AND (SELECT count(c) FROM RunStep c WHERE c.parentStepId = :parentId "
            + "AND c.parentAttempt = :parentAttempt "
            + "AND c.status = com.flowcortex.workflow.domain.RunStepStatus.RUNNING) < :limit")
    int claimRegeneratedChild(@Param("id") UUID id, @Param("parentId") UUID parentId,
            @Param("parentAttempt") short parentAttempt, @Param("limit") int limit, @Param("now") Instant now,
            @Param("queueDeadline") Instant queueDeadline, @Param("input") String input);

    /**
     * {@code completed|error -> pending} as the next attempt of an item: output, stored objects, error,
     * timing, lease, deadlines, progress and delivery count are cleared and the input is kept. The
     * attempt guard decides between two callers re-arming the same item.
     */
    @Modifying
    @Query("UPDATE RunStep r SET r.status = com.flowcortex.workflow.domain.RunStepStatus.PENDING, "
            + "r.attempt = r.attempt + 1, r.output = NULL, r.outputs = NULL, r.errorCode = NULL, "
            + "r.errorMessage = NULL, r.startedAt = NULL, r.finishedAt = NULL, r.pickedUpAt = NULL, "
            + "r.leaseUntil = NULL, r.queueDeadline = NULL, r.runDeadline = NULL, r.progressPercent = NULL, "
            + "r.deliveryCount = 0 "
            + "WHERE r.id = :id AND r.parentStepId IS NOT NULL AND r.attempt = :attempt AND r.attempt < 32767 "
            + "AND r.status IN (com.flowcortex.workflow.domain.RunStepStatus.COMPLETED, "
            + "com.flowcortex.workflow.domain.RunStepStatus.ERROR)")
    int reArmChild(@Param("id") UUID id, @Param("attempt") short attempt);

    /** Every item of the parent that has not started becomes {@code skipped}; returns how many. */
    @Modifying
    @Query("UPDATE RunStep r SET r.status = com.flowcortex.workflow.domain.RunStepStatus.SKIPPED "
            + "WHERE r.parentStepId = :parentId AND r.status = com.flowcortex.workflow.domain.RunStepStatus.PENDING")
    int skipPendingChildren(@Param("parentId") UUID parentId);

    /** Replaces the result of a {@code completed} parent; {@code 0} when it is not {@code completed}. */
    @Modifying
    @Query("UPDATE RunStep r SET r.output = :output, r.outputs = :outputs "
            + "WHERE r.id = :parentId AND r.status = com.flowcortex.workflow.domain.RunStepStatus.COMPLETED")
    int replaceCompletedOutput(@Param("parentId") UUID parentId, @Param("output") String output,
            @Param("outputs") String outputs);

    /**
     * A delivery's {@code started}: the first pickup time is kept; the run deadline restarts
     * unless this is an older delivery than one already seen. Listener threads can apply messages
     * out of order, so the lease and delivery count only ever move forward.
     */
    @Modifying
    @Query("UPDATE RunStep r SET r.pickedUpAt = COALESCE(r.pickedUpAt, :at), "
            + "r.leaseUntil = GREATEST(COALESCE(r.leaseUntil, :leaseUntil), :leaseUntil), "
            + "r.runDeadline = CASE WHEN :deliveryCount >= r.deliveryCount THEN :runDeadline ELSE r.runDeadline END, "
            + "r.deliveryCount = GREATEST(r.deliveryCount, :deliveryCount) "
            + "WHERE r.id = :id AND r.attempt = :attempt "
            + "AND r.status = com.flowcortex.workflow.domain.RunStepStatus.RUNNING")
    int recordPickup(@Param("id") UUID id, @Param("attempt") short attempt, @Param("at") Instant at,
            @Param("leaseUntil") Instant leaseUntil, @Param("runDeadline") Instant runDeadline,
            @Param("deliveryCount") short deliveryCount);

    /**
     * A heartbeat: extends the lease (never backwards); a {@code null} percent keeps the last one.
     * A heartbeat that overtakes {@code started} still counts as pickup and sets the run deadline,
     * but never moves one.
     */
    @Modifying
    @Query("UPDATE RunStep r SET r.leaseUntil = GREATEST(COALESCE(r.leaseUntil, :leaseUntil), :leaseUntil), "
            + "r.progressPercent = COALESCE(:percent, r.progressPercent), "
            + "r.deliveryCount = GREATEST(r.deliveryCount, :deliveryCount), r.pickedUpAt = COALESCE(r.pickedUpAt, :now), "
            + "r.runDeadline = COALESCE(r.runDeadline, :runDeadline) "
            + "WHERE r.id = :id AND r.attempt = :attempt "
            + "AND r.status = com.flowcortex.workflow.domain.RunStepStatus.RUNNING")
    int extendLease(@Param("id") UUID id, @Param("attempt") short attempt, @Param("now") Instant now,
            @Param("leaseUntil") Instant leaseUntil, @Param("percent") Short percent,
            @Param("deliveryCount") short deliveryCount, @Param("runDeadline") Instant runDeadline);

    @Modifying
    @Query("UPDATE RunStep r SET r.status = com.flowcortex.workflow.domain.RunStepStatus.COMPLETED, r.output = :output, "
            + "r.outputs = :outputs, r.finishedAt = :now "
            + "WHERE r.id = :id AND r.runId = :runId AND r.attempt = :attempt "
            + "AND r.status = com.flowcortex.workflow.domain.RunStepStatus.RUNNING")
    int completeIfRunning(@Param("id") UUID id, @Param("runId") UUID runId, @Param("attempt") short attempt,
            @Param("output") String output, @Param("outputs") String outputs, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE RunStep r SET r.status = com.flowcortex.workflow.domain.RunStepStatus.COMPLETED, "
            + "r.output = :output, r.finishedAt = :now "
            + "WHERE r.id = :id AND r.runId = :runId "
            + "AND r.status = com.flowcortex.workflow.domain.RunStepStatus.PENDING")
    int completePendingApproval(@Param("id") UUID id, @Param("runId") UUID runId,
            @Param("output") String output, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE RunStep r SET r.status = com.flowcortex.workflow.domain.RunStepStatus.ERROR, "
            + "r.errorCode = :code, r.errorMessage = :message, r.finishedAt = :now "
            + "WHERE r.id = :id AND r.runId = :runId "
            + "AND r.status = com.flowcortex.workflow.domain.RunStepStatus.PENDING")
    int failPendingApproval(@Param("id") UUID id, @Param("runId") UUID runId, @Param("code") String code,
            @Param("message") String message, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE RunStep r SET r.status = com.flowcortex.workflow.domain.RunStepStatus.ERROR, r.errorCode = :code, "
            + "r.errorMessage = :message, r.finishedAt = :now "
            + "WHERE r.id = :id AND r.runId = :runId AND r.attempt = :attempt "
            + "AND r.status = com.flowcortex.workflow.domain.RunStepStatus.RUNNING")
    int failIfRunning(@Param("id") UUID id, @Param("runId") UUID runId, @Param("attempt") short attempt,
            @Param("code") String code, @Param("message") String message, @Param("now") Instant now);

    /**
     * Running steps with a passed bound: never picked up and past the queue deadline, or picked up
     * and past the lease or the run deadline. Which bound it was is decided by the caller.
     */
    @Query("SELECT r FROM RunStep r WHERE r.status = com.flowcortex.workflow.domain.RunStepStatus.RUNNING AND ("
            + "(r.pickedUpAt IS NULL AND r.queueDeadline < :now) "
            + "OR (r.pickedUpAt IS NOT NULL AND (r.leaseUntil < :now OR r.runDeadline < :now)))")
    List<RunStep> findReapable(@Param("now") Instant now, Pageable page);

    /**
     * Steps of the given types across all runs, newest first: by when the step started, or its run
     * for a step not started yet. {@code status} {@code null} matches all.
     */
    @Query(value = "SELECT s FROM RunStep s JOIN WorkflowRun r ON r.id = s.runId "
            + "WHERE s.type IN :types AND (:status IS NULL OR s.status = :status) "
            + "ORDER BY COALESCE(s.startedAt, r.startedAt) DESC, s.id DESC",
            countQuery = "SELECT count(s) FROM RunStep s WHERE s.type IN :types AND (:status IS NULL OR s.status = :status)")
    Page<RunStep> findByTypeNewestFirst(@Param("types") Collection<String> types,
            @Param("status") RunStepStatus status, Pageable page);
}
