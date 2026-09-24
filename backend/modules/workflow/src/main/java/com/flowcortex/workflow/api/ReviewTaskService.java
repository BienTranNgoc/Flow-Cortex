package com.flowcortex.workflow.api;

import com.flowcortex.workflow.domain.ReviewTask;
import java.util.List;
import java.util.UUID;

public interface ReviewTaskService {

    List<ReviewTask> getTasksByRunId(UUID runId);

    ReviewTask approveTask(UUID taskId, UUID reviewerId, String reviewedPayload);

    ReviewTask rejectTask(UUID taskId, UUID reviewerId);
}
