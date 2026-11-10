package com.flowcortex.workflow.infrastructure;

import com.flowcortex.workflow.domain.WorkflowReviewReport;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Review reports; a member's repeated report of one review keeps the first. */
public interface WorkflowReviewReportRepository
        extends JpaRepository<WorkflowReviewReport, WorkflowReviewReport.Key> {

    /** 1 when the report was written, 0 when the member had already reported the review. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO workflow_review_report (review_id, reporter_id, reason, details, created_at)
            VALUES (:reviewId, :reporterId, :reason, CAST(:details AS varchar), CAST(:now AS timestamptz))
            ON CONFLICT (review_id, reporter_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("reviewId") UUID reviewId, @Param("reporterId") UUID reporterId,
            @Param("reason") String reason, @Param("details") String details, @Param("now") Instant now);
}
