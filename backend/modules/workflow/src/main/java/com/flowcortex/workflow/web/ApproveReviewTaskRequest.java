package com.flowcortex.workflow.web;

import java.util.UUID;

public record ApproveReviewTaskRequest(
    String reviewedPayload,
    UUID revisionId,
    Long expectedVersion
) {}
