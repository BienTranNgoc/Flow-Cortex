package com.flowcortex.workflow.api;

import java.util.List;
import java.util.UUID;

public interface ReviewTaskService {

    List<ReviewTaskView> getTasksByRunId(UUID runId, UUID ownerId);

    ReviewTaskView approveTask(UUID taskId, UUID reviewerId, String reviewedPayload);

    ReviewTaskView rejectTask(UUID taskId, UUID reviewerId);
}
