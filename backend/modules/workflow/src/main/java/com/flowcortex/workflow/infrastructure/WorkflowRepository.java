package com.flowcortex.workflow.infrastructure;

import com.flowcortex.workflow.domain.Workflow;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for {@link Workflow} (design.md D1, task 5.2).
 *
 * <p>Visibility (R-049): a workflow is VISIBLE to a caller when {@code
 * owner_id = caller} OR {@code is_shared = true} — runs stay owner-scoped
 * regardless (see {@code WorkflowRunRepository}), a shared DEFINITION never
 * implies shared RUNS.
 */
public interface WorkflowRepository extends JpaRepository<Workflow, UUID> {

    @Query("SELECT w FROM Workflow w WHERE w.ownerId = :ownerId OR w.shared = true ORDER BY w.name ASC")
    List<Workflow> findAllVisibleTo(@Param("ownerId") UUID ownerId);

    @Query("SELECT w FROM Workflow w WHERE w.id = :id AND (w.ownerId = :ownerId OR w.shared = true)")
    Optional<Workflow> findVisibleTo(@Param("id") UUID id, @Param("ownerId") UUID ownerId);
}
