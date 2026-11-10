package com.flowcortex.workflow.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.workflow.api.WorkflowStepView;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.UUID;

/** {@code inputRef} is null when the step reads the previous step's output; {@code config} is null when unset. */
record WorkflowStepResponse(
        UUID id, int stepOrder, String name, String type,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"inline", "remote"}) String mode,
        @Schema(nullable = true) String inputRef, @Schema(type = "object", nullable = true) JsonNode config) {

    private static final ObjectMapper CONFIG_READER = new ObjectMapper();

    static WorkflowStepResponse from(WorkflowStepView view) {
        return new WorkflowStepResponse(view.id(), view.stepOrder(), view.name(), view.type(), view.mode(),
                view.inputRef(), parse(view));
    }

    private static JsonNode parse(WorkflowStepView view) {
        if (view.config() == null) {
            return null;
        }
        try {
            return CONFIG_READER.readTree(view.config());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("workflow_step " + view.id() + " holds a config that is not JSON", e);
        }
    }
}
