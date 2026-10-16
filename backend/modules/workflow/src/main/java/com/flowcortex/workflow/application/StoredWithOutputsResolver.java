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

final class StoredWithOutputsResolver {

    private static final TypeReference<List<MediaJobInput>> INPUTS = new TypeReference<>() { };
    private static final TypeReference<List<MediaOutput>> OUTPUTS = new TypeReference<>() { };
    private final ObjectMapper json = new ObjectMapper();

    String resolve(String storedInput, String config, Map<Integer, String> outputsByOrder, UUID runId,
            int currentStepOrder) {
        try {
            List<MediaJobInput> resolved = new ArrayList<>(json.readValue(storedInput, INPUTS));
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
                if (sourceOrder >= currentStepOrder) {
                    throw invalid("outputInputs must reference an earlier step");
                }
                String encodedOutputs = outputsByOrder.get(sourceOrder);
                if (encodedOutputs == null) {
                    throw invalid("Step " + sourceOrder + " has no accepted outputs");
                }
                List<MediaOutput> matches = json.readValue(encodedOutputs, OUTPUTS).stream()
                        .filter(output -> outputName.equals(output.name()))
                        .toList();
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
