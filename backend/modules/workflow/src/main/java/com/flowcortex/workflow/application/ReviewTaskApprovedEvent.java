package com.flowcortex.workflow.application;

import java.util.UUID;

record ReviewTaskApprovedEvent(UUID taskId, String payload) {
}
