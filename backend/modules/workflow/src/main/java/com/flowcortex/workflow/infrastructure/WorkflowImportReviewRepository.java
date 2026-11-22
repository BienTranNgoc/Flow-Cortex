package com.flowcortex.workflow.infrastructure;

import com.flowcortex.workflow.domain.WorkflowImportReview;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data JPA repository for {@link WorkflowImportReview}. */
public interface WorkflowImportReviewRepository extends JpaRepository<WorkflowImportReview, UUID> {

    /** Row-locking load, so two imports of one token serialize. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM WorkflowImportReview r WHERE r.token = :token")
    Optional<WorkflowImportReview> findLockedByToken(@Param("token") UUID token);

    long countByUserIdAndWorkflowIdIsNullAndExpiresAtAfter(UUID userId, Instant now);

    /** Deletes reviews that expired without being imported; imported rows stay as the token ledger. */
    @Modifying
    @Query("DELETE FROM WorkflowImportReview r WHERE r.workflowId IS NULL AND r.expiresAt < :now")
    int deleteExpiredUnimported(@Param("now") Instant now);
}
