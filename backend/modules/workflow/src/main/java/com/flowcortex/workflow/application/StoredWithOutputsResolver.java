package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.media.api.MediaJob;
import com.flowcortex.media.api.MediaJobInput;
import com.flowcortex.media.api.MediaOutput;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The job inputs of a remote step whose config declares {@code outputInputs}: the references stored
 * when its run started (one object reference, or an ordered list of them), then one input per entry
 * {@code {stepOrder, outputName, inputKey, optional?}} naming an object an earlier step of the same
 * run recorded. An {@code optional} entry whose object that step did not record is left out.
 */
final class StoredWithOutputsResolver {

    private static final TypeReference<List<MediaJobInput>> INPUTS = new TypeReference<>() { };
    private static final TypeReference<List<MediaOutput>> OUTPUTS = new TypeReference<>() { };
    private static final ObjectMapper CONFIG_READER = new ObjectMapper();
    private final ObjectMapper json = new ObjectMapper();

    String resolve(String storedInput, String config, Map<Integer, String> outputsByOrder, UUID runId,
            int currentStepOrder) {
        try {
            List<MediaJobInput> resolved = new ArrayList<>(storedInputs(storedInput));
            Set<String> keys = new HashSet<>();
            resolved.forEach(input -> requireUnique(keys, input.key()));
            JsonNode mappings = json.readTree(config).get("outputInputs");
            if (mappings == null || !mappings.isArray() || mappings.isEmpty()) {
                throw invalid("Step config needs a non-empty outputInputs array");
            }
            for (JsonNode mapping : mappings) {
                int sourceOrder = requiredPositiveInt(mapping, "stepOrder");
                String outputName = requiredText(mapping, "outputName");
                String inputKey = requiredText(mapping, "inputKey");
                boolean optional = optional(mapping);
                if (sourceOrder >= currentStepOrder) {
                    throw invalid("outputInputs must reference an earlier step");
                }
                String encodedOutputs = outputsByOrder.get(sourceOrder);
                if (encodedOutputs == null) {
                    if (optional) {
                        continue;
                    }
                    throw invalid("Step " + sourceOrder + " has no accepted outputs");
                }
                List<MediaOutput> matches = json.readValue(encodedOutputs, OUTPUTS).stream()
                        .filter(output -> outputName.equals(output.name()))
                        .toList();
                if (matches.isEmpty() && optional) {
                    continue;
                }
                if (matches.size() != 1) {
                    throw invalid("Step " + sourceOrder + " must have exactly one output named " + outputName);
                }
                MediaOutput output = matches.getFirst();
                if (!MediaJob.isBelow(MediaJob.outputPrefix(runId, sourceOrder), output.objectKey())) {
                    throw invalid("Step output is outside its run prefix");
                }
                requireUnique(keys, inputKey);
                resolved.add(new MediaJobInput(inputKey, output.bucket(), output.objectKey()));
            }
            return json.writeValueAsString(resolved);
        } catch (StepFailedException e) {
            throw e;
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw invalid("Stored inputs, outputInputs, or step outputs are not valid JSON", e);
        }
    }

    /**
     * What is wrong with the {@code outputInputs} that step {@code stepOrder}'s {@code config}
     * declares, or {@code null} when it declares none or they are well-formed, read only earlier
     * steps and give each input key once. Checked when a workflow is created.
     */
    static String declarationFault(String config, int stepOrder) {
        if (config == null || config.isBlank()) {
            return null;
        }
        JsonNode root;
        try {
            root = CONFIG_READER.readTree(config);
        } catch (JsonProcessingException e) {
            return "config is not JSON";
        }
        if (root == null || !root.isObject()) {
            return "config is not a JSON object";
        }
        JsonNode mappings = root.get("outputInputs");
        if (mappings == null) {
            return null;
        }
        if (!mappings.isArray() || mappings.isEmpty()) {
            return "outputInputs must be a non-empty list of {stepOrder, outputName, inputKey} entries";
        }
        Set<String> keys = new HashSet<>();
        for (JsonNode mapping : mappings) {
            int sourceOrder;
            String inputKey;
            try {
                sourceOrder = requiredPositiveInt(mapping, "stepOrder");
                requiredText(mapping, "outputName");
                inputKey = requiredText(mapping, "inputKey");
                optional(mapping);
            } catch (StepFailedException e) {
                return e.getMessage();
            }
            if (sourceOrder >= stepOrder) {
                return "outputInputs reads step " + sourceOrder + ", which does not run before step " + stepOrder;
            }
            if (!keys.add(inputKey)) {
                return "outputInputs gives input key '" + inputKey + "' twice";
            }
        }
        return null;
    }

    /** Whether {@code config}, a JSON object or {@code null}, has an {@code outputInputs} entry. */
    static boolean declaresOutputInputs(String config) {
        if (config == null || config.isBlank()) {
            return false;
        }
        try {
            JsonNode root = CONFIG_READER.readTree(config);
            return root != null && root.has("outputInputs");
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    private List<MediaJobInput> storedInputs(String storedInput) throws JsonProcessingException {
        if (storedInput != null && json.readTree(storedInput).isObject()) {
            return List.of(json.readValue(storedInput, MediaJobInput.class));
        }
        return json.readValue(storedInput, INPUTS);
    }

    private static boolean optional(JsonNode node) {
        JsonNode value = node.get("optional");
        if (value == null) {
            return false;
        }
        if (!value.isBoolean()) {
            throw invalid("outputInputs.optional must be true or false");
        }
        return value.booleanValue();
    }

    private static int requiredPositiveInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToInt() || value.intValue() < 1) {
            throw invalid("outputInputs." + field + " must be a positive integer");
        }
        return value.intValue();
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw invalid("outputInputs." + field + " must be a non-empty string");
        }
        return value.textValue();
    }

    private static void requireUnique(Set<String> keys, String key) {
        if (key == null || !keys.add(key)) {
            throw invalid("Combined input keys must be non-empty and unique");
        }
    }

    private static StepFailedException invalid(String message) {
        return new StepFailedException("contract_violation", message);
    }

    private static StepFailedException invalid(String message, Exception cause) {
        return new StepFailedException("contract_violation", message, cause);
    }
}
