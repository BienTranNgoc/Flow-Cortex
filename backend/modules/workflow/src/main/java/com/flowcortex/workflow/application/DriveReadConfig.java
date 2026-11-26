package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.connector.api.DriveFolderId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The config of a {@code drive.read} step: one folder, either given here or taken from the step's input, and how
 * many files to read. {@link #parse} is the one reader, used when a workflow is saved and again when the step runs.
 * {@code folderId} is {@code null} when the folder comes from the input.
 */
record DriveReadConfig(String folderId, boolean folderFromInput, int maxFiles) {

    /** Either the parsed config or the fault that refused it; exactly one is non-null. */
    record Result(DriveReadConfig config, String fault) {
    }

    static final int DEFAULT_MAX_FILES = 20;
    static final int MAX_FILES = 50;

    private static final ObjectMapper READER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> KEYS = Set.of("folderId", "folderFromInput", "maxFiles");
    private static final String SOURCE_FAULT = "give exactly one of folderId and folderFromInput: true";
    private static final int MAX_NAMED_LENGTH = 64;

    static Result parse(String configJson) {
        JsonNode root;
        try {
            root = configJson == null || configJson.isBlank() ? null : READER.readTree(configJson);
        } catch (JsonProcessingException e) {
            return refused("config is not valid JSON");
        }
        if (root == null) {
            return refused("config is required");
        }
        if (!root.isObject()) {
            return refused("config must be a JSON object");
        }
        String fault = unknownKeyFault(root);
        Boolean fromInput = null;
        if (fault == null) {
            JsonNode switchNode = root.get("folderFromInput");
            if (switchNode != null && !switchNode.isBoolean()) {
                fault = "folderFromInput must be true or false";
            } else if (switchNode != null) {
                fromInput = switchNode.asBoolean();
            }
        }
        String folderId = null;
        if (fault == null) {
            fault = sourceFault(root, fromInput);
        }
        if (fault == null && root.has("folderId")) {
            Optional<String> parsed = root.get("folderId").isTextual()
                    ? DriveFolderId.parse(root.get("folderId").asText()) : Optional.empty();
            if (parsed.isEmpty()) {
                fault = "folderId must be the link of a Google Drive folder or a folder id";
            } else {
                folderId = parsed.get();
            }
        }
        int maxFiles = DEFAULT_MAX_FILES;
        if (fault == null && root.has("maxFiles")) {
            JsonNode node = root.get("maxFiles");
            if (!node.isInt() || node.intValue() < 1 || node.intValue() > MAX_FILES) {
                fault = "maxFiles must be a whole number from 1 to " + MAX_FILES;
            } else {
                maxFiles = node.intValue();
            }
        }
        if (fault != null) {
            return refused(fault);
        }
        return new Result(new DriveReadConfig(folderId, Boolean.TRUE.equals(fromInput), maxFiles), null);
    }

    /** What is wrong with {@code configJson}, or {@code null} when it is a valid config. */
    static String fault(String configJson) {
        return parse(configJson).fault();
    }

    private static Result refused(String fault) {
        return new Result(null, fault);
    }

    private static String unknownKeyFault(JsonNode root) {
        List<String> unknown = new ArrayList<>();
        root.fieldNames().forEachRemaining(name -> {
            if (!KEYS.contains(name)) {
                unknown.add(shorten(name));
            }
        });
        return unknown.isEmpty() ? null
                : "config has unknown key '" + unknown.get(0) + "'; allowed: folderId, folderFromInput, maxFiles";
    }

    /** Exactly one source: a folderId, or the switch set to true; {@code folderFromInput: false} names no source. */
    private static String sourceFault(JsonNode root, Boolean fromInput) {
        boolean hasFolderId = root.has("folderId");
        boolean takesInput = Boolean.TRUE.equals(fromInput);
        return hasFolderId == takesInput ? SOURCE_FAULT : null;
    }

    private static String shorten(String name) {
        return name.length() <= MAX_NAMED_LENGTH ? name : name.substring(0, MAX_NAMED_LENGTH) + "...";
    }
}
