package com.flowcortex.workflow.application;

import java.util.UUID;

record ReviewTaskApprovedEvent(UUID taskId, UUID runId, String payload, UUID revisionId) {
}
