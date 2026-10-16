package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.media.api.MediaJob;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.media.api.ProviderJob;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
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
    public Duration maxRuntime() {
        return mediaJobs.providerLimits().maxRuntime();
    }

    @Override
    public Duration queueTimeout(Duration lease) {
        return mediaJobs.providerLimits().queueTimeout();
    }

    @Override
    public String execute(StepContext context) {
        Map<String, Object> payload = payload(context.input());
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("profile", profile(context.config()));
        params.put("payload", payload);
        ProviderJob job = new ProviderJob("1", context.stepId(), context.runId(), context.runId(),
                context.stepOrder(), capability, params,
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
}
