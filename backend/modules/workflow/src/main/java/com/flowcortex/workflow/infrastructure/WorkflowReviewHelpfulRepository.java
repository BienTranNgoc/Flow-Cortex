package com.flowcortex.workflow.infrastructure;

import com.flowcortex.workflow.domain.WorkflowReviewHelpful;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Helpful marks. Both writes return the number of rows they changed, which is the helpful count's delta. */
public interface WorkflowReviewHelpfulRepository
        extends JpaRepository<WorkflowReviewHelpful, WorkflowReviewHelpful.Key> {

    /** 1 when the mark was written, 0 when the member had already marked the review. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO workflow_review_helpful (review_id, user_id, created_at)
            VALUES (:reviewId, :userId, CAST(:now AS timestamptz))
            ON CONFLICT (review_id, user_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("reviewId") UUID reviewId, @Param("userId") UUID userId, @Param("now") Instant now);

    /** 1 when the mark was removed, 0 when there was none. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM WorkflowReviewHelpful h WHERE h.reviewId = :reviewId AND h.userId = :userId")
    int deleteMark(@Param("reviewId") UUID reviewId, @Param("userId") UUID userId);

    /** The ids among {@code reviewIds} the member marked helpful. */
    @Query("SELECT h.reviewId FROM WorkflowReviewHelpful h WHERE h.userId = :userId AND h.reviewId IN :reviewIds")
    List<UUID> findMarkedReviewIds(@Param("userId") UUID userId, @Param("reviewIds") Collection<UUID> reviewIds);
}
