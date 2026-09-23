package com.flowcortex.workflow.infrastructure;

import com.flowcortex.workflow.domain.WorkflowRun;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link WorkflowRun}. {@link
 * #findByIdAndOwnerId} is the enforcement point: a run is owner-scoped
 * ALWAYS, even for a run of a shared workflow definition — a lookup outside
 * the caller's own id returns empty, which the application layer turns into
 * a 404 ({@code NotFoundException}), matching {@code :modules:user}'s
 * cross-scope pattern.
 */
public interface WorkflowRunRepository extends JpaRepository<WorkflowRun, UUID> {

    Optional<WorkflowRun> findByIdAndOwnerId(UUID id, UUID ownerId);
}
