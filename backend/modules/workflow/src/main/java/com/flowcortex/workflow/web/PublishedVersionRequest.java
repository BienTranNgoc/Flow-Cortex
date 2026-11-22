package com.flowcortex.workflow.web;

import java.util.UUID;

/** The version the published pointer moves to; a missing id is refused by the service with 422. */
record PublishedVersionRequest(UUID versionId) {
}
