package com.flowcortex.llm.api;

/** A function the model may call; {@code parametersSchemaJson} is the provider's schema object as JSON text. */
public record LlmTool(String name, String description, String parametersSchemaJson) {

    public LlmTool {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }
}
