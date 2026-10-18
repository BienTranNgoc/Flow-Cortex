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

    List<RunStep> findByRunIdOrderByStepOrderAsc(UUID runId);

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

    long countByRunIdAndStatusNot(UUID runId, RunStepStatus status);

    @Modifying
    @Query("UPDATE RunStep r SET r.status = com.flowcortex.workflow.domain.RunStepStatus.RUNNING, r.startedAt = :now, "
            + "r.queueDeadline = :queueDeadline, r.input = :input "
            + "WHERE r.id = :id AND r.status = com.flowcortex.workflow.domain.RunStepStatus.PENDING")
    int claimPending(@Param("id") UUID id, @Param("now") Instant now, @Param("queueDeadline") Instant queueDeadline,
            @Param("input") String input);

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
