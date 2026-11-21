package com.flowcortex.workflow.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.flowcortex.workflow.application.DataStoreJson;
import java.io.UncheckedIOException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

/** Hands a stored value to the Jackson 3 response converter without rounding its numbers. */
final class DataStoreWire {

    private static final JsonMapper WIRE = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();

    private DataStoreWire() {
    }

    static tools.jackson.databind.JsonNode toWire(JsonNode stored) {
        try {
            return WIRE.readTree(DataStoreJson.mapper().writeValueAsString(stored));
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
