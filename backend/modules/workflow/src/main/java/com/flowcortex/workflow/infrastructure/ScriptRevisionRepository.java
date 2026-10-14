package com.flowcortex.workflow.infrastructure;

import com.flowcortex.workflow.domain.ScriptRevision;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Revisions are read whole and in order — a task's history is its script's audit trail, and a
 * page of them would hide the one the reviewer needs to see. Lookups are task-scoped so a caller
 * cannot name a revision that belongs to another task.
 */
public interface ScriptRevisionRepository extends JpaRepository<ScriptRevision, UUID> {

    Optional<ScriptRevision> findByIdAndReviewTaskId(UUID id, UUID reviewTaskId);

    List<ScriptRevision> findByReviewTaskIdOrderByRevisionNumberAsc(UUID reviewTaskId);

    long countByReviewTaskId(UUID reviewTaskId);
}
