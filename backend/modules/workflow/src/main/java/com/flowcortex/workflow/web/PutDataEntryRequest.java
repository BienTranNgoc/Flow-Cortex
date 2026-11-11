package com.flowcortex.workflow.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.workflow.application.DataStoreJson;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.HttpStatus;

/**
 * {@code value} is any JSON, a JSON {@code null} included; {@code dataType} is left out to keep an existing entry's type
 * (a new entry is {@code json}). The controller takes the body as text and parses it here, because only the store's
 * own mapper keeps a decimal number's digits.
 */
record PutDataEntryRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Any JSON value", implementation = Object.class) JsonNode value,
        @Schema(nullable = true) String dataType) {

    /** A 400 for anything but a JSON object holding {@code value} and, when present, a text or null {@code dataType}. */
    static PutDataEntryRequest parse(String body) {
        JsonNode root;
        try {
            root = body == null ? null : DataStoreJson.mapper().readTree(body);
        } catch (JsonProcessingException | NumberFormatException e) {
            // Jackson lets an exponent beyond ten digits escape as a NumberFormatException, not a parse error.
            throw invalid();
        }
        if (root == null || !root.isObject() || !root.has("value")) {
            throw invalid();
        }
        JsonNode type = root.get("dataType");
        if (type != null && !type.isNull() && !type.isTextual()) {
            throw invalid();
        }
        return new PutDataEntryRequest(root.get("value"), type == null || type.isNull() ? null : type.textValue());
    }

    private static LocalizedStatusException invalid() {
        return new LocalizedStatusException(HttpStatus.BAD_REQUEST, new LocalizedMessage("workflow.dataStore.bodyInvalid"));
    }
}
