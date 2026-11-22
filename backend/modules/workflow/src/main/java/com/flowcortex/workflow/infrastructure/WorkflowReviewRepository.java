package com.flowcortex.workflow.infrastructure;

import com.flowcortex.workflow.domain.ReviewSort;
import com.flowcortex.workflow.domain.WorkflowReview;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Reviews of workflows. A review is looked up by workflow and member, or by id only together with
 * its workflow, so an id of another workflow's review is never found.
 */
public interface WorkflowReviewRepository extends JpaRepository<WorkflowReview, UUID> {

    String FILTERED = "r.workflowId = :workflowId AND (:rating IS NULL OR r.rating = :rating) "
            + "AND (:withComment = false OR r.body IS NOT NULL) "
            + "AND (:withImages = false OR EXISTS (SELECT 1 FROM WorkflowReviewImage i WHERE i.reviewId = r.id))";

    Optional<WorkflowReview> findByWorkflowIdAndUserId(UUID workflowId, UUID userId);

    /** Row-locking load, so concurrent writes of one member's review serialize. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM WorkflowReview r WHERE r.workflowId = :workflowId AND r.userId = :userId")
    Optional<WorkflowReview> lockByWorkflowIdAndUserId(@Param("workflowId") UUID workflowId,
            @Param("userId") UUID userId);

    /** Creates the review with no helpful marks; returns 1 when written, 0 when the member already has one. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO workflow_review (id, workflow_id, user_id, rating, body, helpful_count, created_at, updated_at)
            VALUES (:id, :workflowId, :userId, :rating, CAST(:body AS varchar), 0,
                    CAST(:now AS timestamptz), CAST(:now AS timestamptz))
            ON CONFLICT (workflow_id, user_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("workflowId") UUID workflowId, @Param("userId") UUID userId,
            @Param("rating") int rating, @Param("body") String body, @Param("now") Instant now);

    Optional<WorkflowReview> findByIdAndWorkflowId(UUID id, UUID workflowId);

    /**
     * Loads the review and holds a key-share row lock until the transaction ends, so a concurrent delete waits
     * while rows referencing the review are inserted. Unlike a share lock it does not conflict with
     * {@link #addHelpful}, so two members marking one review cannot deadlock.
     */
    @Query(value = "SELECT * FROM workflow_review WHERE id = :id AND workflow_id = :workflowId FOR KEY SHARE",
            nativeQuery = true)
    Optional<WorkflowReview> lockByIdAndWorkflowId(@Param("id") UUID id, @Param("workflowId") UUID workflowId);

    /** Adds {@code delta} to the helpful count in the database; never computed from a read. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE WorkflowReview r SET r.helpfulCount = r.helpfulCount + :delta WHERE r.id = :id")
    int addHelpful(@Param("id") UUID id, @Param("delta") int delta);

    @Query("SELECT r.rating AS rating, count(r) AS total FROM WorkflowReview r "
            + "WHERE r.workflowId = :workflowId GROUP BY r.rating")
    List<RatingCount> ratingCounts(@Param("workflowId") UUID workflowId);

    @Query("SELECT r FROM WorkflowReview r WHERE " + FILTERED + " ORDER BY r.createdAt DESC, r.id DESC")
    List<WorkflowReview> pageNewest(@Param("workflowId") UUID workflowId, @Param("rating") Integer rating,
            @Param("withComment") boolean withComment, @Param("withImages") boolean withImages, Pageable page);

    @Query("SELECT r FROM WorkflowReview r WHERE " + FILTERED
            + " ORDER BY r.helpfulCount DESC, r.createdAt DESC, r.id DESC")
    List<WorkflowReview> pageHelpful(@Param("workflowId") UUID workflowId, @Param("rating") Integer rating,
            @Param("withComment") boolean withComment, @Param("withImages") boolean withImages, Pageable page);

    /** One page of the workflow's reviews; {@code rating} {@code null} matches every rating. */
    default List<WorkflowReview> page(UUID workflowId, Integer rating, boolean withComment, boolean withImages,
            ReviewSort sort, Pageable page) {
        return switch (sort) {
            case NEWEST -> pageNewest(workflowId, rating, withComment, withImages, page);
            case HELPFUL -> pageHelpful(workflowId, rating, withComment, withImages, page);
        };
    }

    /** The number of reviews {@link #page} filters out of, with the same filters. */
    @Query("SELECT count(r) FROM WorkflowReview r WHERE " + FILTERED)
    long count(@Param("workflowId") UUID workflowId, @Param("rating") Integer rating,
            @Param("withComment") boolean withComment, @Param("withImages") boolean withImages);
}
