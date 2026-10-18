package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.media.api.MediaJob;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.media.api.provider.ProviderInput;
import com.flowcortex.media.api.provider.ProviderJob;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Dispatches provider-neutral script, speech and generated-video jobs. */
class ProviderStepExecutor implements StepExecutor {

    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() { };

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String capability;
    private final String profile;
    private final MediaJobs mediaJobs;
    private final Clock clock;

    ProviderStepExecutor(String capability, String profile, MediaJobs mediaJobs, Clock clock) {
        this.capability = capability;
        this.profile = profile;
        this.mediaJobs = mediaJobs;
        this.clock = clock;
    }

    @Override
    public String type() {
        return capability;
    }

    @Override
    public Mode mode() {
        return Mode.REMOTE;
    }

    @Override
    public boolean bindsRunInputs() {
        return "generate_script".equals(capability) || "generate_video".equals(capability);
    }

    @Override
    public Duration maxRuntime() {
        return mediaJobs.providerLimits().maxRuntime();
    }

    @Override
    public Duration queueTimeout(Duration lease) {
        return mediaJobs.providerLimits().queueTimeout();
    }

    @Override
    public String execute(StepContext context) {
        Map<String, Object> payload = mappedPayload(payload(context.input()), context.config());
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("profile", profile(context.config()));
        params.put("payload", payload);
        ProviderJob job = new ProviderJob("1", context.stepId(), context.runId(), context.runId(),
                context.stepOrder(), capability, params, providerInputs(context.storedInput(), context.config()),
                MediaJob.outputPrefix(context.runId(), context.stepOrder(), context.attempt()),
                (int) context.attempt(), Math.toIntExact(maxRuntime().toSeconds()), clock.instant());
        try {
            mediaJobs.enqueueProvider(job);
        } catch (ConstraintViolationException e) {
            throw new StepFailedException("contract_violation",
                    "The provider job breaks its contract: " + e.getMessage(), e);
        }
        return null;
    }

    /** Script and video use the first immutable product image; config remains a legacy fallback. */
    private List<ProviderInput> providerInputs(String storedInput, String config) {
        if (!bindsRunInputs()) {
            return List.of();
        }
        if (storedInput != null && !storedInput.isBlank()) {
            try {
                List<ProviderInput> inputs = objectMapper.readValue(storedInput, new TypeReference<>() { });
                return inputs.isEmpty() ? List.of() : List.of(inputs.getFirst());
            } catch (JsonProcessingException e) {
                throw new StepFailedException("contract_violation",
                        "Provider image input is not a valid ordered list", e);
            }
        }
        if (config == null || config.isBlank()) {
            return List.of();
        }
        try {
            JsonNode tree = objectMapper.readTree(config);
            JsonNode ref = tree.get("referenceInput");
            if (ref == null || ref.isNull()) {
                return List.of();
            }
            if (!ref.isObject()) {
                throw new StepFailedException("contract_violation",
                        "referenceInput must be a single {key, bucket, objectKey} object");
            }
            return List.of(objectMapper.treeToValue(ref, ProviderInput.class));
        } catch (JsonProcessingException e) {
            throw new StepFailedException("contract_violation",
                    "referenceInput is not a valid object reference", e);
        }
    }

    private Map<String, Object> payload(String input) {
        if (input == null || input.isBlank()) {
            throw new StepFailedException("contract_violation", "The provider step has no input");
        }
        try {
            return objectMapper.readValue(input, OBJECT);
        } catch (JsonProcessingException e) {
            throw new StepFailedException("contract_violation", "Provider input is not valid JSON", e);
        }
    }

    private String profile(String config) {
        if (config == null || config.isBlank()) {
            return profile;
        }
        try {
            Object configured = objectMapper.readValue(config, OBJECT).get("profile");
            if (configured instanceof String value && !value.isBlank()) {
                return value;
            }
            throw new StepFailedException("contract_violation", "Provider step config needs a profile");
        } catch (JsonProcessingException e) {
            throw new StepFailedException("contract_violation", "Provider step config is not valid JSON", e);
        }
    }

    private Map<String, Object> mappedPayload(Map<String, Object> input, String config) {
        if (config == null || config.isBlank()) {
            return input;
        }
        try {
            Object mapping = objectMapper.readValue(config, OBJECT).get("payloadFields");
            if (mapping == null) {
                return input;
            }
            if (!(mapping instanceof Map<?, ?> fields) || fields.isEmpty()) {
                throw new StepFailedException("contract_violation",
                        "Provider payloadFields must be a non-empty object");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> field : fields.entrySet()) {
                if (!(field.getKey() instanceof String target) || target.isBlank()
                        || !(field.getValue() instanceof String source) || source.isBlank()
                        || !input.containsKey(source)) {
                    throw new StepFailedException("contract_violation",
                            "Provider payloadFields contains an invalid or missing source field");
                }
                result.put(target, input.get(source));
            }
            appendPromptInstruction(result, objectMapper.readValue(config, OBJECT));
            return result;
        } catch (JsonProcessingException e) {
            throw new StepFailedException("contract_violation", "Provider step config is not valid JSON", e);
        }
    }

    private void appendPromptInstruction(Map<String, Object> payload, Map<String, Object> config) {
        Object instruction = config.get("promptInstruction");
        if (instruction == null) {
            return;
        }
        if (!(instruction instanceof String text) || text.isBlank()
                || !(payload.get("prompt") instanceof String prompt) || prompt.isBlank()) {
            throw new StepFailedException("contract_violation",
                    "Provider promptInstruction requires a non-empty prompt and instruction");
        }
        payload.put("prompt", prompt + "\n\nVisual direction: " + text);
    }
}
