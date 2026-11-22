package com.flowcortex.workflow.infrastructure;

import com.flowcortex.workflow.domain.WorkflowReviewImage;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Images of reviews. */
public interface WorkflowReviewImageRepository extends JpaRepository<WorkflowReviewImage, UUID> {

    List<WorkflowReviewImage> findByReviewIdOrderByPosition(UUID reviewId);

    /** The images of several reviews, grouped by review, each review's in display order. */
    List<WorkflowReviewImage> findByReviewIdInOrderByReviewIdAscPosition(Collection<UUID> reviewIds);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM WorkflowReviewImage i WHERE i.reviewId = :reviewId")
    int deleteByReviewId(@Param("reviewId") UUID reviewId);
}
