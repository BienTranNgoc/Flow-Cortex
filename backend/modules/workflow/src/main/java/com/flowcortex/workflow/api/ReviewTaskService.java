package com.flowcortex.workflow.api;

import java.util.List;
import java.util.UUID;

public interface ReviewTaskService {

    List<ReviewTaskView> getTasksByRunId(UUID runId, UUID ownerId);

    ReviewTaskView approveTask(UUID taskId, UUID reviewerId, String reviewedPayload,
            UUID revisionId, Long expectedVersion);

    ScriptRevisionView saveRevision(UUID taskId, UUID reviewerId, String content, long expectedVersion);

    List<ScriptRevisionView> getRevisions(UUID taskId, UUID ownerId);

    ReviewTaskView rejectTask(UUID taskId, UUID reviewerId);
}
