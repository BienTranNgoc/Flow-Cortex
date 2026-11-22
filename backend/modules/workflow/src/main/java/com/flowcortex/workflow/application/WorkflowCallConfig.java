package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Iterator;
import java.util.UUID;

/**
 * The config of a {@code workflow.call} step: a JSON object whose only key is {@code workflowId}, a
 * UUID string. Faults name the field and never echo a caller's value.
 */
record WorkflowCallConfig(UUID workflowId) {

    private static final ObjectMapper READER = new ObjectMapper();
    private static final String KEY = "workflowId";
    private static final String NOT_AN_ID = KEY + " must be a workflow id (UUID string)";
    private static final String NOT_AN_OBJECT = "config must be a JSON object with one key, " + KEY;
    private static final int UUID_LENGTH = 36;
    private static final int MAX_KEY_ECHO = 40;

    /** The parsed config, or the fault that stopped it; exactly one is non-null. */
    record Result(WorkflowCallConfig config, String fault) {
    }

    static Result parse(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return failed("config is required: {\"workflowId\": \"<workflow id>\"}");
        }
        JsonNode root;
        try {
            root = READER.readTree(configJson);
        } catch (JsonProcessingException e) {
            return failed(NOT_AN_OBJECT);
        }
        if (root == null || !root.isObject()) {
            return failed(NOT_AN_OBJECT);
        }
        Iterator<String> names = root.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!KEY.equals(name)) {
                return failed("'" + echo(name) + "' is not allowed; " + KEY + " is the only key");
            }
        }
        JsonNode id = root.get(KEY);
        if (id == null || !id.isTextual() || id.textValue().length() != UUID_LENGTH) {
            return failed(NOT_AN_ID);
        }
        try {
            return new Result(new WorkflowCallConfig(UUID.fromString(id.textValue())), null);
        } catch (IllegalArgumentException e) {
            return failed(NOT_AN_ID);
        }
    }

    /** What is wrong with {@code configJson}, or {@code null} when it is a valid call config. */
    static String fault(String configJson) {
        return parse(configJson).fault();
    }

    private static Result failed(String message) {
        return new Result(null, message);
    }

    private static String echo(String name) {
        return name.length() <= MAX_KEY_ECHO ? name : name.substring(0, MAX_KEY_ECHO) + "...";
    }
}
