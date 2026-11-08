package com.flowcortex.workflow.application;

import com.flowcortex.realtime.api.TopicAuthorizer;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Claims {@code run:<uuid>} topics; only the run's owner may read one. */
@Component
class RunTopicAuthorizer implements TopicAuthorizer {

    private static final String PREFIX = "run:";

    private final WorkflowRunRepository workflowRunRepository;

    RunTopicAuthorizer(WorkflowRunRepository workflowRunRepository) {
        this.workflowRunRepository = workflowRunRepository;
    }

    /** Only the canonical lowercase form is claimed: it is the form the publisher builds the topic from. */
    @Override
    public boolean supports(String topic) {
        return runId(topic) != null;
    }

    @Override
    public boolean authorize(UUID userId, String topic) {
        UUID runId = runId(topic);
        return runId != null && workflowRunRepository.findByIdAndOwnerId(runId, userId).isPresent();
    }

    private static UUID runId(String topic) {
        if (topic == null || !topic.startsWith(PREFIX)) {
            return null;
        }
        String id = topic.substring(PREFIX.length());
        try {
            UUID parsed = UUID.fromString(id);
            return parsed.toString().equals(id) ? parsed : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
