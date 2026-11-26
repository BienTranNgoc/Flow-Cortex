package com.flowcortex.workflow.infrastructure;

import com.flowcortex.workflow.domain.WorkflowModerationAction;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** The moderation history of a workflow. */
public interface WorkflowModerationActionRepository extends JpaRepository<WorkflowModerationAction, UUID> {

    /** Newest first; ties on the timestamp break on the id. */
    List<WorkflowModerationAction> findByWorkflowIdOrderByCreatedAtDescIdDesc(UUID workflowId);
}
