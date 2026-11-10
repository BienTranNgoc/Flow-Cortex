package com.flowcortex.workflow.web;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;

/**
 * The exported workflow file (format version 1), declared only so the published contract names the body the
 * export answers and the import review accepts; the handlers move the file as raw JSON.
 */
@Schema(name = "WorkflowArtifact")
record WorkflowArtifactDocument(
        @Schema(requiredMode = RequiredMode.REQUIRED, minimum = "1", maximum = "1") int formatVersion,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant exportedAt,
        @Schema(requiredMode = RequiredMode.REQUIRED) Workflow workflow,
        @Schema(requiredMode = RequiredMode.REQUIRED) List<Step> steps) {

    @Schema(name = "WorkflowArtifactWorkflow")
    record Workflow(@Schema(requiredMode = RequiredMode.REQUIRED, minLength = 1, maxLength = 255) String name) {
    }

    @Schema(name = "WorkflowArtifactStep")
    record Step(
            @Schema(requiredMode = RequiredMode.REQUIRED, minimum = "1") int stepOrder,
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, maxLength = 255) String name,
            @Schema(requiredMode = RequiredMode.REQUIRED, minLength = 1, maxLength = 50) String type,
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, maxLength = 255) String inputRef,
            @Schema(requiredMode = RequiredMode.REQUIRED, type = "object", nullable = true) JsonNode config) {
    }
}
