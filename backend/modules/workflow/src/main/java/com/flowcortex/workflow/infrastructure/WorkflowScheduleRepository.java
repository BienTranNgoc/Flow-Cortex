package com.flowcortex.workflow.infrastructure;

import com.flowcortex.workflow.domain.WorkflowSchedule;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spring Data JPA repository for {@link WorkflowSchedule}. Every write is one statement keyed by the
 * owner and decided in the database: {@link #upsert} on the unique (owner, workflow) pair, {@link #pause}
 * and {@link #resume} on the status they leave. A statement that matches no row answers {@code 0}, which
 * the caller reads as "already there", "not found" or, for resume, "the rule changed" — never a
 * read-then-write.
 */
public interface WorkflowScheduleRepository extends JpaRepository<WorkflowSchedule, UUID> {

    Optional<WorkflowSchedule> findByOwnerIdAndWorkflowId(UUID ownerId, UUID workflowId);

    boolean existsByOwnerIdAndStatus(UUID ownerId, String status);

    /**
     * Creates the owner's schedule for the workflow or replaces the existing one, keeping its id and
     * creation time. {@code nextRunAt} is the next fire of an {@code active} schedule and {@code null}
     * for a paused one. A replace that pauses keeps the first pause time of a schedule that was already paused.
     */
    @Transactional
    @Query(value = """
            INSERT INTO workflow_schedule
                (id, owner_id, workflow_id, frequency, day_of_week, day_of_month, local_time, time_zone,
                 status, paused_at, next_run_at, input, acknowledge_cost, created_at, updated_at)
            VALUES (:id, :ownerId, :workflowId, :frequency, CAST(:dayOfWeek AS smallint),
                    CAST(:dayOfMonth AS smallint), :localTime, :timeZone,
                    CASE WHEN :active THEN 'active' ELSE 'paused' END,
                    CASE WHEN :active THEN NULL ELSE CAST(:now AS timestamptz) END,
                    CAST(:nextRunAt AS timestamptz), CAST(:input AS text), :acknowledgeCost,
                    CAST(:now AS timestamptz), CAST(:now AS timestamptz))
            ON CONFLICT (owner_id, workflow_id) DO UPDATE
               SET frequency = EXCLUDED.frequency,
                   day_of_week = EXCLUDED.day_of_week,
                   day_of_month = EXCLUDED.day_of_month,
                   local_time = EXCLUDED.local_time,
                   time_zone = EXCLUDED.time_zone,
                   status = EXCLUDED.status,
                   paused_at = CASE WHEN EXCLUDED.status = 'paused'
                                    THEN COALESCE(workflow_schedule.paused_at, EXCLUDED.paused_at) END,
                   next_run_at = EXCLUDED.next_run_at,
                   input = EXCLUDED.input,
                   acknowledge_cost = EXCLUDED.acknowledge_cost,
                   updated_at = EXCLUDED.updated_at
            RETURNING *
            """, nativeQuery = true)
    WorkflowSchedule upsert(@Param("id") UUID id, @Param("ownerId") UUID ownerId,
            @Param("workflowId") UUID workflowId, @Param("frequency") String frequency,
            @Param("dayOfWeek") Short dayOfWeek, @Param("dayOfMonth") Short dayOfMonth,
            @Param("localTime") LocalTime localTime, @Param("timeZone") String timeZone,
            @Param("active") boolean active, @Param("nextRunAt") Instant nextRunAt,
            @Param("input") String input, @Param("acknowledgeCost") boolean acknowledgeCost,
            @Param("now") Instant now);

    /** {@code active -> paused}; {@code 0} when the owner has no active schedule for the workflow. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE workflow_schedule
               SET status = 'paused', paused_at = :now, next_run_at = NULL, updated_at = :now
             WHERE owner_id = :ownerId AND workflow_id = :workflowId AND status = 'active'
            """, nativeQuery = true)
    int pause(@Param("ownerId") UUID ownerId, @Param("workflowId") UUID workflowId, @Param("now") Instant now);

    /**
     * {@code paused -> active} with the next fire the caller computed from the given rule. The write also
     * requires the stored rule to still equal that rule, so a concurrent replace that kept the schedule paused
     * cannot end up with the next fire of the rule it replaced. Returns {@code 0} when the owner has no paused
     * schedule for the workflow or its rule changed since the caller read it.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE workflow_schedule
               SET status = 'active', paused_at = NULL, next_run_at = :nextRunAt, updated_at = :now
             WHERE owner_id = :ownerId AND workflow_id = :workflowId AND status = 'paused'
               AND frequency = :frequency
               AND day_of_week IS NOT DISTINCT FROM CAST(:dayOfWeek AS smallint)
               AND day_of_month IS NOT DISTINCT FROM CAST(:dayOfMonth AS smallint)
               AND local_time = :localTime AND time_zone = :timeZone
            """, nativeQuery = true)
    int resume(@Param("ownerId") UUID ownerId, @Param("workflowId") UUID workflowId,
            @Param("frequency") String frequency, @Param("dayOfWeek") Short dayOfWeek,
            @Param("dayOfMonth") Short dayOfMonth, @Param("localTime") LocalTime localTime,
            @Param("timeZone") String timeZone, @Param("nextRunAt") Instant nextRunAt, @Param("now") Instant now);

    /** Removes the owner's schedule; its fires stay with {@code schedule_id} cleared. Returns the rows removed. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "DELETE FROM workflow_schedule WHERE owner_id = :ownerId AND workflow_id = :workflowId",
            nativeQuery = true)
    int deleteByOwnerIdAndWorkflowId(@Param("ownerId") UUID ownerId, @Param("workflowId") UUID workflowId);
}
