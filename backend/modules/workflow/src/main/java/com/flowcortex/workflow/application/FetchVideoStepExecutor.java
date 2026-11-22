package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.media.api.MediaCapability;
import com.flowcortex.media.api.MediaJob;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.media.api.MediaLimits;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Downloads the video behind a link on the fetch worker. The link is the config's {@code url}, else the
 * step's resolved input; the step reads no uploaded file, so its job has no inputs. Like
 * {@link RemoteStepExecutor}, {@link #execute} runs inside the claim transaction and returns {@code null}.
 */
class FetchVideoStepExecutor implements StepExecutor {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MediaJobs mediaJobs;
    private final Clock clock;

    FetchVideoStepExecutor(MediaJobs mediaJobs, Clock clock) {
        this.mediaJobs = mediaJobs;
        this.clock = clock;
    }

    @Override
    public String type() {
        return FetchVideoConfig.TYPE;
    }

    @Override
    public Mode mode() {
        return Mode.REMOTE;
    }

    @Override
    public InputPolicy inputPolicy() {
        return InputPolicy.RESOLVED;
    }

    @Override
    public boolean bindsRunInputs() {
        return false;
    }

    @Override
    public Duration maxRuntime() {
        return mediaJobs.limitsFor(FetchVideoConfig.TYPE).maxRuntime();
    }

    @Override
    public Duration queueTimeout(Duration lease) {
        return mediaJobs.limitsFor(FetchVideoConfig.TYPE).queueTimeout();
    }

    @Override
    public String execute(StepContext context) {
        if (!mediaJobs.isEnabled()) {
            throw new StepFailedException("media_disabled", "Media engine is currently disabled");
        }
        String configFault = FetchVideoConfig.fault(context.config());
        if (configFault != null) {
            throw new StepFailedException("contract_violation", "The step config is not valid: " + configFault);
        }
        String link = FetchVideoConfig.configUrl(context.config())
                .orElseGet(() -> context.input() == null ? null : context.input().trim());
        if (FetchVideoConfig.linkFault(link) != null) {
            throw new StepFailedException("input_rejected", "The step needs a link that starts with http:// or https://");
        }
        MediaLimits limits = mediaJobs.limitsFor(FetchVideoConfig.TYPE);
        MediaJob job = new MediaJob("1", context.stepId(), context.runId(), context.runId(), context.stepOrder(),
                MediaCapability.FETCH_VIDEO, List.of(), params(link, context.config()),
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

    private Map<String, Object> params(String link, String config) {
        JsonNode settings = readConfig(config);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("url", link);
        params.put("quality", settings.hasNonNull("quality") ? settings.get("quality").textValue() : "best");
        params.put("extractAudio", !settings.hasNonNull("extractAudio") || settings.get("extractAudio").booleanValue());
        return params;
    }

    private JsonNode readConfig(String config) {
        if (config == null || config.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(config);
        } catch (JsonProcessingException e) {
            throw new StepFailedException("contract_violation", "The step config is not a JSON object", e);
        }
    }
}
