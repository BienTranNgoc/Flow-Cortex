package com.flowcortex.workflow.web;

import com.flowcortex.workflow.application.WorkflowDataStore;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import tools.jackson.databind.JsonNode;

/** One data-store entry with its value. */
record DataEntryResponse(
        String key,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Any JSON value", implementation = Object.class) JsonNode value,
        String dataType, int sizeBytes, Instant createdAt, Instant updatedAt) {

    static DataEntryResponse from(WorkflowDataStore.Entry entry) {
        return new DataEntryResponse(entry.key(), DataStoreJson.toWire(entry.value()), entry.dataType(), entry.sizeBytes(),
                entry.createdAt(), entry.updatedAt());
    }
}
