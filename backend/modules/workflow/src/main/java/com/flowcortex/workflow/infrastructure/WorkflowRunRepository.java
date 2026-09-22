package com.flowcortex.workflow.infrastructure;

import com.flowcortex.workflow.domain.WorkflowRun;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link WorkflowRun} (design.md D5, task
 * 5.2). {@link #findByIdAndOwnerId} is the enforcement point of R-047: a run
 * is owner-scoped ALWAYS, even for a run of a shared workflow definition — a
 * lookup outside the caller's own id returns empty, which the application
 * layer turns into a 404 ({@code NotFoundException}), matching R-025's
 * cross-scope pattern in {@code :modules:user}.
 */
public interface WorkflowRunRepository extends JpaRepository<WorkflowRun, UUID> {

    Optional<WorkflowRun> findByIdAndOwnerId(UUID id, UUID ownerId);
}
