package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
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
 * <p>The job's inputs are those the step declared, as the API resolved them from what it recorded
 * for the run; a step that declares none gets its stored input, the object reference the run start
 * resolved from the caller's own upload. Nothing a client sent reaches a job.
 */
class RemoteStepExecutor implements StepExecutor {

    private static final TypeReference<Map<String, Object>> PARAMS = new TypeReference<>() { };

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
        List<MediaJobInput> inputs = context.jobInputs() != null ? context.jobInputs()
                : List.of(DeclaredInputs.uploadReference(context.input()));
        MediaJob job = new MediaJob("1", context.stepId(), context.runId(), context.runId(), context.stepOrder(),
                MediaCapability.fromValue(capability), inputs, params(context.config()),
                MediaJob.outputPrefix(context.runId(), context.stepOrder()), (int) context.attempt(),
                Math.toIntExact(limits.maxRuntime().toSeconds()), limits.maxInputBytes(), clock.instant());
        try {
            mediaJobs.enqueue(job);
        } catch (ConstraintViolationException e) {
            throw new StepFailedException("contract_violation", "The media job breaks its contract: " + e.getMessage(),
                    e);
        }
        return null;
    }

    /** The step's config as job params, without the input declaration the API has already resolved. */
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
        params.remove(DeclaredInputs.CONFIG_KEY);
        return params;
    }
}
