package com.flowcortex.workflow.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.UncheckedIOException;

/** Bridges the Jackson 3 nodes the HTTP converter binds and the Jackson 2 nodes the data store holds, via JSON text. */
final class DataStoreJson {

    private static final ObjectMapper STORE = new ObjectMapper();
    private static final tools.jackson.databind.json.JsonMapper WIRE =
            tools.jackson.databind.json.JsonMapper.builder().build();

    private DataStoreJson() {
    }

    static JsonNode toStore(tools.jackson.databind.JsonNode wire) {
        try {
            return STORE.readTree(WIRE.writeValueAsString(wire));
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    static tools.jackson.databind.JsonNode toWire(JsonNode stored) {
        try {
            return WIRE.readTree(STORE.writeValueAsString(stored));
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
