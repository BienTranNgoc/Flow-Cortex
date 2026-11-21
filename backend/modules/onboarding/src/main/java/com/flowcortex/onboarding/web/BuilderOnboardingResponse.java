package com.flowcortex.onboarding.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The caller's builder onboarding state and when it was completed (null while not). */
@Schema(name = "BuilderOnboardingResponse")
record BuilderOnboardingResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED, type = "object") Map<String, Object> state,
        @Schema(requiredMode = RequiredMode.REQUIRED, types = {"string", "null"}, format = "date-time")
        Instant completedAt) {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> STATE = new TypeReference<>() { };

    static BuilderOnboardingResponse of(ObjectNode state, Instant completedAt) {
        return new BuilderOnboardingResponse(MAPPER.convertValue(state, STATE), completedAt);
    }
}
