package com.flowcortex.workflow.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The exported workflow file (format version 1), declared only so the published contract names the body the
 * export answers and the import review accepts; the handlers move the file as raw JSON. A step's config is a
 * {@code Map} here because springdoc publishes a Jackson {@code JsonNode} as a string.
 */
@Schema(name = "WorkflowArtifact")
record WorkflowArtifactDocument(
        @Schema(requiredMode = RequiredMode.REQUIRED, minimum = "1", maximum = "1") int formatVersion,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant exportedAt,
        @Schema(requiredMode = RequiredMode.REQUIRED) Workflow workflow,
        @Schema(requiredMode = RequiredMode.REQUIRED) List<Step> steps,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED,
                description = "The workflow's data-store entries; absent when it has none") List<DataEntry> dataStore) {

    @Schema(name = "WorkflowArtifactWorkflow")
    record Workflow(@Schema(requiredMode = RequiredMode.REQUIRED, minLength = 1, maxLength = 255) String name) {
    }

    @Schema(name = "WorkflowArtifactStep")
    record Step(
            @Schema(requiredMode = RequiredMode.REQUIRED, minimum = "1", maximum = "32767") int stepOrder,
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, maxLength = 255) String name,
            @Schema(requiredMode = RequiredMode.REQUIRED, minLength = 1, maxLength = 50) String type,
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, maxLength = 255) String inputRef,
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) Map<String, Object> config) {
    }

    @Schema(name = "WorkflowArtifactDataEntry")
    record DataEntry(
            @Schema(requiredMode = RequiredMode.REQUIRED) String key,
            @Schema(requiredMode = RequiredMode.REQUIRED) String dataType,
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Any JSON value",
                    implementation = Object.class) Object value) {
    }
}
