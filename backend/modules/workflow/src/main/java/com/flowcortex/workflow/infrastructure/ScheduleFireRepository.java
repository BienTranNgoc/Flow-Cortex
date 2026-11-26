package com.flowcortex.workflow.infrastructure;

import com.flowcortex.workflow.domain.ScheduleFire;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spring Data JPA repository for {@link ScheduleFire}. A fire is inserted {@code starting} and then moves
 * once, to {@code started} or {@code failed}; each move is guarded on {@code status = 'starting'}, so a late
 * or repeated call changes nothing and answers {@code 0}.
 */
public interface ScheduleFireRepository extends JpaRepository<ScheduleFire, UUID> {

    /**
     * Inserts a {@code starting} fire for the owner's schedule in one statement and returns the rows
     * inserted: {@code 0} when the schedule is gone or is not the owner's. The schedule row is locked
     * {@code FOR KEY SHARE}, so a delete committing between the check and the insert makes the select
     * return nothing instead of the insert failing on the foreign key.
     */
    @Transactional
    @Modifying
    @Query(value = """
            INSERT INTO workflow_schedule_fire
                (id, owner_id, workflow_id, schedule_id, trigger, scheduled_at, status, request_key, created_at)
            SELECT CAST(:id AS uuid), s.owner_id, s.workflow_id, s.id, CAST(:trigger AS varchar),
                   CAST(:scheduledAt AS timestamptz), 'starting', CAST(:requestKey AS text),
                   CAST(:now AS timestamptz)
              FROM workflow_schedule s
             WHERE s.id = :scheduleId AND s.owner_id = :ownerId
               FOR KEY SHARE OF s
            """, nativeQuery = true)
    int recordForSchedule(@Param("id") UUID id, @Param("scheduleId") UUID scheduleId,
            @Param("ownerId") UUID ownerId, @Param("trigger") String trigger,
            @Param("scheduledAt") Instant scheduledAt, @Param("requestKey") String requestKey,
            @Param("now") Instant now);

    /** {@code starting -> started}; {@code 0} when the fire is not {@code starting}. */
    @Transactional
    @Modifying
    @Query(value = "UPDATE workflow_schedule_fire SET status = 'started' WHERE id = :id AND status = 'starting'",
            nativeQuery = true)
    int markStarted(@Param("id") UUID id);

    /**
     * {@code starting -> failed} with the refusal's code, message key and arguments ({@code argsJson} is a JSON
     * array of strings and numbers, or {@code null}); {@code 0} when the fire is not {@code starting}.
     */
    @Transactional
    @Modifying
    @Query(value = """
            UPDATE workflow_schedule_fire
               SET status = 'failed', error_code = :code, error_key = :key, error_args = CAST(:argsJson AS jsonb)
             WHERE id = :id AND status = 'starting'
            """, nativeQuery = true)
    int markFailed(@Param("id") UUID id, @Param("code") String code, @Param("key") String key,
            @Param("argsJson") String argsJson);

    /**
     * The owner's fires of one workflow, newest first, each with the run holding its request key. The join
     * has no deleted filter: a deleted run is reported through {@code runDeletedAt}, never hidden.
     */
    @Query(value = "SELECT new com.flowcortex.workflow.infrastructure.ScheduleFireRow(f, r.id, r.status, r.deletedAt) "
            + "FROM ScheduleFire f LEFT JOIN WorkflowRun r ON r.ownerId = f.ownerId AND r.requestKey = f.requestKey "
            + "WHERE f.ownerId = :ownerId AND f.workflowId = :workflowId "
            + "ORDER BY f.createdAt DESC, f.id DESC",
            countQuery = "SELECT count(f) FROM ScheduleFire f "
                    + "WHERE f.ownerId = :ownerId AND f.workflowId = :workflowId")
    Page<ScheduleFireRow> findHistory(@Param("ownerId") UUID ownerId, @Param("workflowId") UUID workflowId,
            Pageable page);
}
