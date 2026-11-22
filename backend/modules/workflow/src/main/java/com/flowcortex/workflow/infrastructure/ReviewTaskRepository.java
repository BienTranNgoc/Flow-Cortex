package com.flowcortex.workflow.infrastructure;

import com.flowcortex.workflow.domain.ReviewTask;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every {@code @Modifying} write below also moves {@code version}: an entity read before it and
 * saved after fails its flush on {@code @Version} instead of writing its stale columns over the
 * conditional update.
 */
public interface ReviewTaskRepository extends JpaRepository<ReviewTask, UUID> {

    List<ReviewTask> findByRunId(UUID runId);

    Optional<ReviewTask> findByRunStepId(UUID runStepId);

    /** The task's run, without loading the task into the persistence context. */
    @Query("SELECT t.runId FROM ReviewTask t WHERE t.id = :id")
    Optional<UUID> findRunIdById(@Param("id") UUID id);

    /** Ends a still-pending task as {@code expired}; {@code 0} when it was decided or expired already. */
    @Modifying
    @Transactional
    @Query(value = "UPDATE review_task SET status = 'expired', version = version + 1 "
            + "WHERE id = :id AND status = 'pending'", nativeQuery = true)
    int expireIfPending(@Param("id") UUID id);

    /** Pending tasks whose time limit has passed, the longest overdue first. */
    @Query("SELECT t.id FROM ReviewTask t WHERE t.status = com.flowcortex.workflow.domain.ReviewTaskStatus.PENDING "
            + "AND t.expiresAt <= :now ORDER BY t.expiresAt, t.id")
    List<UUID> findOverdueIds(@Param("now") Instant now, Pageable page);

    /** Stores a QA review's questions and marks them ready; {@code 0} unless they are still being generated. */
    @Modifying
    @Transactional
    @Query(value = "UPDATE review_task SET questions = CAST(:questions AS jsonb), questions_state = 'ready', "
            + "version = version + 1 WHERE id = :id AND questions_state = 'generating'", nativeQuery = true)
    int storeQuestionsIfGenerating(@Param("id") UUID id, @Param("questions") String questions);

    /** QA reviews still generating questions that were created before {@code createdBefore}, oldest first. */
    @Query("SELECT t.id FROM ReviewTask t WHERE t.questionsState = 'generating' AND t.createdAt < :createdBefore "
            + "ORDER BY t.createdAt, t.id")
    List<UUID> findStaleGeneratingIds(@Param("createdBefore") Instant createdBefore, Pageable page);
}
