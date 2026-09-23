package com.flowcortex.workflow.infrastructure;

import com.flowcortex.workflow.domain.WorkflowRun;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link WorkflowRun}. {@link #findByIdAndOwnerId} is the
 * enforcement point: runs are always owner-scoped (even for a shared workflow's run) — a
 * lookup outside the caller's id returns empty, which the application layer turns into a 404.
 */
public interface WorkflowRunRepository extends JpaRepository<WorkflowRun, UUID> {

    Optional<WorkflowRun> findByIdAndOwnerId(UUID id, UUID ownerId);
}
