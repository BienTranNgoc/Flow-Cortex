package com.flowcortex.workflow.infrastructure;

import com.flowcortex.workflow.domain.WorkflowVersionPin;
import com.flowcortex.workflow.domain.WorkflowVersionPinId;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data JPA repository for {@link WorkflowVersionPin}; writes go through {@link #upsert}. */
public interface WorkflowVersionPinRepository extends JpaRepository<WorkflowVersionPin, WorkflowVersionPinId> {

    List<WorkflowVersionPin> findByUserIdAndWorkflowIdIn(UUID userId, Collection<UUID> workflowIds);

    /** One statement, so two concurrent first pins by the same member leave one row instead of a key clash. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO workflow_version_pin (user_id, workflow_id, pinned_version, reason, pinned_at)
            VALUES (:userId, :workflowId, :version, :reason, :pinnedAt)
            ON CONFLICT (user_id, workflow_id) DO UPDATE
               SET pinned_version = EXCLUDED.pinned_version, reason = EXCLUDED.reason, pinned_at = EXCLUDED.pinned_at
            """, nativeQuery = true)
    int upsert(@Param("userId") UUID userId, @Param("workflowId") UUID workflowId, @Param("version") int version,
            @Param("reason") String reason, @Param("pinnedAt") Instant pinnedAt);
}
