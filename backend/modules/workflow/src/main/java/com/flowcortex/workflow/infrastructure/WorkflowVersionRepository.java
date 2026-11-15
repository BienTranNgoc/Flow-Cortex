package com.flowcortex.workflow.infrastructure;

import com.flowcortex.workflow.domain.WorkflowVersion;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for {@link WorkflowVersion}. The {@code ReleasedAtIsNotNull}
 * variants serve non-owners, who only ever reach released versions.
 */
public interface WorkflowVersionRepository extends JpaRepository<WorkflowVersion, UUID> {

    Optional<WorkflowVersion> findTopByWorkflowIdOrderByVersionDesc(UUID workflowId);

    Optional<WorkflowVersion> findByWorkflowIdAndVersion(UUID workflowId, int version);

    Optional<WorkflowVersion> findByWorkflowIdAndVersionAndReleasedAtIsNotNull(UUID workflowId, int version);

    Optional<WorkflowVersion> findByIdAndWorkflowId(UUID id, UUID workflowId);

    boolean existsByWorkflowIdAndVersion(UUID workflowId, int version);

    List<WorkflowVersion> findByWorkflowIdOrderByVersionDesc(UUID workflowId, Pageable page);

    List<WorkflowVersion> findByWorkflowIdAndVersionLessThanOrderByVersionDesc(UUID workflowId, int beforeVersion,
            Pageable page);

    List<WorkflowVersion> findByWorkflowIdAndReleasedAtIsNotNullOrderByVersionDesc(UUID workflowId, Pageable page);

    List<WorkflowVersion> findByWorkflowIdAndVersionLessThanAndReleasedAtIsNotNullOrderByVersionDesc(
            UUID workflowId, int beforeVersion, Pageable page);

    /** The versions a set of workflows' pointers name; one query for a whole page of workflows. */
    List<WorkflowVersion> findByIdIn(Collection<UUID> ids);

    /** The versions the caller pinned among the given workflows, one row per pin. */
    @Query("""
            SELECT v FROM WorkflowVersion v, WorkflowVersionPin p
            WHERE p.userId = :userId AND p.workflowId IN :workflowIds
              AND v.workflowId = p.workflowId AND v.version = p.pinnedVersion
            """)
    List<WorkflowVersion> findPinnedBy(@Param("userId") UUID userId, @Param("workflowIds") Collection<UUID> workflowIds);
}
