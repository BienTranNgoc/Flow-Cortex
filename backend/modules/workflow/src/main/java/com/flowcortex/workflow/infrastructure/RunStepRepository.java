package com.flowcortex.workflow.infrastructure;

import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.domain.RunStepStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for {@link RunStep} (design.md D5, task 5.2).
 *
 * <p>{@link #claimPending} is the single-active-dispatch guard (R-045): one
 * atomic {@code UPDATE ... WHERE id = ? AND status = 'pending'} — a
 * conditional write acted on by ROW COUNT, not an application-level
 * read-then-check-then-write (which would race). Exactly one of two
 * concurrent callers claiming the same step gets {@code 1} back; the other
 * gets {@code 0} and must not re-execute the step.
 */
public interface RunStepRepository extends JpaRepository<RunStep, UUID> {

    List<RunStep> findByRunIdOrderByStepOrderAsc(UUID runId);

    long countByRunIdAndStatusNot(UUID runId, RunStepStatus status);

    @Modifying
    @Query("UPDATE RunStep r SET r.status = com.flowcortex.workflow.domain.RunStepStatus.RUNNING, r.startedAt = :now "
            + "WHERE r.id = :id AND r.status = com.flowcortex.workflow.domain.RunStepStatus.PENDING")
    int claimPending(@Param("id") UUID id, @Param("now") Instant now);
}
