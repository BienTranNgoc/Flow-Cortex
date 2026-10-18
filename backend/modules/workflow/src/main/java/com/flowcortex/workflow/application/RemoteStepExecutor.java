package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.media.api.MediaCapability;
import com.flowcortex.media.api.MediaJob;
import com.flowcortex.media.api.MediaJobInput;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.media.api.MediaLimits;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * A media capability: the step becomes a job for the worker. {@link #execute} runs inside the
 * claim transaction, because {@link MediaJobs#enqueue} records the job in the caller's
 * transaction — a claim that rolls back publishes nothing. The {@code media} module owns the
 * kill switch and the per-capability limits; this class only reads them.
 *
 * <p>The job's input starts with the asset references snapshotted at run creation. Capabilities
 * that declare {@link InputPolicy#STORED_WITH_OUTPUTS} also receive configured outputs of earlier
 * completed steps after {@link RunAdvancer} validates and appends them.
 */
class RemoteStepExecutor implements StepExecutor {

    private static final TypeReference<Map<String, Object>> PARAMS = new TypeReference<>() { };
    /** A run binds at most this many uploaded assets ({@link MediaJobs#bindRunInputs}). */
    private static final int MAX_BOUND_ASSETS = 10;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String capability;
    private final MediaJobs mediaJobs;
    private final Clock clock;

    RemoteStepExecutor(String capability, MediaJobs mediaJobs, Clock clock) {
        this.capability = capability;
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
    public InputPolicy inputPolicy() {
        return InputPolicy.STORED;
    }

    @Override
    public InputPolicy inputPolicy(String config) {
        if (config == null || config.isBlank()) {
            return InputPolicy.STORED;
        }
        try {
            return objectMapper.readTree(config).has("outputInputs")
                    ? InputPolicy.STORED_WITH_OUTPUTS
                    : InputPolicy.STORED;
        } catch (JsonProcessingException e) {
            throw new StepFailedException("contract_violation", "The step config is not a JSON object", e);
        }
    }

    @Override
    public Duration maxRuntime() {
        return mediaJobs.limitsFor(capability).maxRuntime();
    }

    @Override
    public Duration queueTimeout(Duration lease) {
        return mediaJobs.limitsFor(capability).queueTimeout();
    }

    @Override
    public String execute(StepContext context) {
        if (!mediaJobs.isEnabled()) {
            throw new StepFailedException("media_disabled", "Media engine is currently disabled");
        }
        MediaLimits limits = mediaJobs.limitsFor(capability);
        MediaJob job = new MediaJob("1", context.stepId(), context.runId(), context.runId(), context.stepOrder(),
                MediaCapability.fromValue(capability), jobInputs(context.input(), context.config()),
                params(context.config()),
                MediaJob.outputPrefix(context.runId(), context.stepOrder(), context.attempt()), (int) context.attempt(),
                Math.toIntExact(limits.maxRuntime().toSeconds()), limits.maxInputBytes(), clock.instant());
        try {
            mediaJobs.enqueue(job);
        } catch (ConstraintViolationException e) {
            throw new StepFailedException("contract_violation", "The media job breaks its contract: " + e.getMessage(),
                    e);
        }
        return null;
    }

    /**
     * The {@code {key, bucket, objectKey}} references stored on the step when its run started, plus
     * one per {@code outputInputs} entry of its config once {@link RunAdvancer} has appended them.
     */
    private List<MediaJobInput> jobInputs(String storedInput, String config) {
        if (storedInput == null || storedInput.isBlank()) {
            throw new StepFailedException("contract_violation", "The step has no input object to process");
        }
        try {
            if (objectMapper.readTree(storedInput).isArray()) {
                List<MediaJobInput> inputs = objectMapper.readValue(storedInput, new TypeReference<>() { });
                int maximum = MAX_BOUND_ASSETS + outputInputCount(config);
                if (inputs.isEmpty() || inputs.size() > maximum) {
                    throw new StepFailedException("contract_violation",
                            "The step must have between 1 and " + maximum + " inputs");
                }
                return inputs;
            }
            return List.of(objectMapper.readValue(storedInput, MediaJobInput.class));
        } catch (JsonProcessingException e) {
            throw new StepFailedException("contract_violation",
                    "The step input is not an object reference or an ordered list of object references", e);
        }
    }

    private int outputInputCount(String config) {
        if (config == null || config.isBlank()) {
            return 0;
        }
        try {
            JsonNode mappings = objectMapper.readTree(config).get("outputInputs");
            return mappings != null && mappings.isArray() ? mappings.size() : 0;
        } catch (JsonProcessingException e) {
            throw new StepFailedException("contract_violation", "The step config is not a JSON object", e);
        }
    }

    private Map<String, Object> params(String config) {
        if (config == null || config.isBlank()) {
            return Map.of();
        }
        Map<String, Object> params;
        try {
            params = objectMapper.readValue(config, PARAMS);
        } catch (JsonProcessingException e) {
            throw new StepFailedException("contract_violation", "The step config is not a JSON object", e);
        }
        if (params == null) {
            throw new StepFailedException("contract_violation", "The step config is not a JSON object");
        }
        return params;
    }
}
