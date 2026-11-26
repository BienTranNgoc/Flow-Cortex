package com.flowcortex.workflow.infrastructure;

import com.flowcortex.workflow.domain.WorkflowReport;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Member reports of workflows; a member's repeated report of one workflow keeps the first. */
public interface WorkflowReportRepository extends JpaRepository<WorkflowReport, UUID> {

    /** Row-locking load: a report is changed only under this lock, so two reviewers serialize. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM WorkflowReport r WHERE r.id = :id")
    Optional<WorkflowReport> findByIdForUpdate(@Param("id") UUID id);

    boolean existsByWorkflowIdAndReporterId(UUID workflowId, UUID reporterId);

    long countByReporterIdAndCreatedAtAfter(UUID reporterId, Instant after);

    /** 1 when the report was written, 0 when the member had already reported the workflow. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO workflow_report (id, workflow_id, reporter_id, reason, details, created_at)
            VALUES (:id, :workflowId, :reporterId, :reason, CAST(:details AS varchar), CAST(:now AS timestamptz))
            ON CONFLICT (workflow_id, reporter_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("workflowId") UUID workflowId,
            @Param("reporterId") UUID reporterId, @Param("reason") String reason,
            @Param("details") String details, @Param("now") Instant now);
}
