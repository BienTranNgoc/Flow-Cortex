package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.media.api.MediaJobInput;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The job inputs a remote step declares in its config, {@code "inputs": {"<key>": "<source>"}}, a
 * source being {@code upload} (the run's upload), {@code step:<n>/<name>} (the object {@code name}
 * step {@code n} recorded) or {@code step:<n>/*} (every object step {@code n} recorded, each keyed
 * by its own name). Workflow creation checks the declaration with {@link #parse}; dispatch turns it
 * into job inputs with {@link #resolve}, only ever from what the API recorded for the run.
 */
final class DeclaredInputs {

    /** The config key holding the declaration; it never reaches the worker as a param. */
    static final String CONFIG_KEY = "inputs";
    static final String ALL = "*";
    private static final String UPLOAD = "upload";
    private static final Pattern STEP_SOURCE = Pattern.compile("step:([1-9][0-9]{0,3})/([^/]+)");
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** The run's upload when {@code stepOrder} is 0, else object {@code name} ({@link #ALL}: all of them) of that step. */
    record Source(int stepOrder, String name) {

        boolean isUpload() {
            return stepOrder == 0;
        }
    }

    private DeclaredInputs() {
    }

    /**
     * The sources {@code config} declares, in declaration order; empty when it declares none.
     * @throws IllegalArgumentException naming the fault, if {@code config} is not a JSON object, or
     *         {@code inputs} is not an object mapping non-empty keys to well-formed sources
     */
    static Map<String, Source> parse(String config) {
        if (config == null || config.isBlank()) {
            return Map.of();
        }
        JsonNode root;
        try {
            root = JSON.readTree(config);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("config is not JSON");
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("config is not a JSON object");
        }
        JsonNode inputs = root.get(CONFIG_KEY);
        if (inputs == null) {
            return Map.of();
        }
        if (!inputs.isObject()) {
            throw new IllegalArgumentException("inputs must map each input key to a source");
        }
        Map<String, Source> sources = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> input : inputs.properties()) {
            String key = input.getKey();
            if (key.isBlank()) {
                throw new IllegalArgumentException("an input key is empty");
            }
            if (!input.getValue().isTextual()) {
                throw new IllegalArgumentException("input '" + key + "' needs a source: upload, step:<n>/<name> or "
                        + "step:<n>/*");
            }
            sources.put(key, source(key, input.getValue().asText()));
        }
        return sources;
    }

    private static Source source(String key, String text) {
        if (UPLOAD.equals(text)) {
            return new Source(0, null);
        }
        Matcher step = STEP_SOURCE.matcher(text);
        if (!step.matches()) {
            throw new IllegalArgumentException("input '" + key + "' has source '" + text
                    + "'; use upload, step:<n>/<name> or step:<n>/*");
        }
        return new Source(Integer.parseInt(step.group(1)), step.group(2));
    }

    /**
     * The job inputs step {@code order} declares in {@code config}, or {@code null} when it declares
     * none. {@code upload} is the run's stored upload reference; {@code objectsByOrder} holds the
     * recorded outputs JSON of each completed step.
     * @throws StepFailedException {@code contract_violation} if the declaration is malformed, reads a
     *         step that does not run before this one, names an object that step did not record, asks
     *         for every object of a step that recorded none, or would send two inputs under one key
     */
    static List<MediaJobInput> resolve(int order, String config, String upload, Map<Integer, String> objectsByOrder) {
        Map<String, Source> sources;
        try {
            sources = parse(config);
        } catch (IllegalArgumentException e) {
            throw contractViolation("The step's declared inputs are malformed: " + e.getMessage());
        }
        if (sources.isEmpty()) {
            return null;
        }
        Map<String, MediaJobInput> inputs = new LinkedHashMap<>();
        for (Map.Entry<String, Source> declared : sources.entrySet()) {
            String key = declared.getKey();
            Source source = declared.getValue();
            if (source.isUpload()) {
                MediaJobInput reference = uploadReference(upload);
                add(inputs, order, new MediaJobInput(key, reference.bucket(), reference.objectKey()));
                continue;
            }
            if (source.stepOrder() >= order) {
                throw contractViolation("Input " + key + " reads step " + source.stepOrder()
                        + ", which does not run before step " + order);
            }
            List<StoredObject> recorded = recordedObjects(objectsByOrder.get(source.stepOrder()));
            if (ALL.equals(source.name())) {
                if (recorded.isEmpty()) {
                    throw contractViolation("Step " + source.stepOrder() + " recorded no outputs");
                }
                for (StoredObject object : recorded) {
                    add(inputs, order, new MediaJobInput(object.name(), object.bucket(), object.objectKey()));
                }
                continue;
            }
            StoredObject object = recorded.stream()
                    .filter(candidate -> source.name().equals(candidate.name()))
                    .findFirst()
                    .orElseThrow(() -> contractViolation(
                            "Step " + source.stepOrder() + " has no output named " + source.name()));
            add(inputs, order, new MediaJobInput(key, object.bucket(), object.objectKey()));
        }
        return List.copyOf(inputs.values());
    }

    private static void add(Map<String, MediaJobInput> inputs, int order, MediaJobInput input) {
        if (inputs.putIfAbsent(input.key(), input) != null) {
            throw contractViolation("Step " + order + " would receive two inputs named " + input.key());
        }
    }

    /** The {@code {key, bucket, objectKey}} reference the run start stored on the step. */
    static MediaJobInput uploadReference(String upload) {
        if (upload == null || upload.isBlank()) {
            throw contractViolation("The step has no input object to process");
        }
        try {
            return JSON.readValue(upload, MediaJobInput.class);
        } catch (JsonProcessingException e) {
            throw new StepFailedException("contract_violation",
                    "The step input is not an object reference {key, bucket, objectKey}", e);
        }
    }

    private static List<StoredObject> recordedObjects(String outputs) {
        if (outputs == null) {
            return List.of();
        }
        try {
            return List.of(JSON.readValue(outputs, StoredObject[].class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A step holds outputs that are not an output list", e);
        }
    }

    private static StepFailedException contractViolation(String message) {
        return new StepFailedException("contract_violation", message);
    }

    /** Where a recorded output is stored. */
    private record StoredObject(String name, String bucket, String objectKey) {
    }
}
