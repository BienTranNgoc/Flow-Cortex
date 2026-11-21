package com.flowcortex.workflow.web;

import com.flowcortex.workflow.api.VersionSavedView;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

/** {@code deduplicated} is true when nothing new was written and {@code version} is the newest existing one. */
record VersionSavedResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) WorkflowVersionResponse version,
        @Schema(requiredMode = RequiredMode.REQUIRED) boolean deduplicated) {

    static VersionSavedResponse from(VersionSavedView view) {
        return new VersionSavedResponse(WorkflowVersionResponse.from(view.version()), view.deduplicated());
    }
}
