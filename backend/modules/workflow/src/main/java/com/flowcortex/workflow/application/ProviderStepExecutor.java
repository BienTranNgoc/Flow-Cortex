package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.media.api.MediaJob;
import com.flowcortex.media.api.MediaJobInput;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.media.api.provider.ProviderInput;
import com.flowcortex.media.api.provider.ProviderJob;
import com.flowcortex.media.api.provider.VendorLedger;
import com.flowcortex.workflow.application.vendor.VendorJobLedger;
import com.flowcortex.workflow.application.vendor.VendorJobsProperties;
import com.flowcortex.workflow.application.vendor.VendorRequestKey;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

/** Dispatches provider-neutral script, speech and generated-video jobs. */
class ProviderStepExecutor implements StepExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(ProviderStepExecutor.class);

    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() { };
    private static final TypeReference<List<MediaJobInput>> INPUTS = new TypeReference<>() { };

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String capability;
    private final String profile;
    private final MediaJobs mediaJobs;
    private final Clock clock;
    private final VendorJobLedger ledger;
    private final VendorJobsProperties vendorJobs;

    ProviderStepExecutor(String capability, String profile, MediaJobs mediaJobs, Clock clock) {
        this(capability, profile, mediaJobs, clock, null, null);
    }

    /** With a ledger, a {@code generate_video} job carries the vendor-job block; other capabilities ignore it. */
    ProviderStepExecutor(String capability, String profile, MediaJobs mediaJobs, Clock clock, VendorJobLedger ledger,
            VendorJobsProperties vendorJobs) {
        this.capability = capability;
        this.profile = profile;
        this.mediaJobs = mediaJobs;
        this.clock = clock;
        this.ledger = ledger;
        this.vendorJobs = vendorJobs;
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
        return "generate_script".equals(capability)
                || "generate_storyboard".equals(capability)
                || "generate_keyframe".equals(capability)
                || "generate_video".equals(capability);
    }

    @Override
    public Duration maxRuntime() {
        return mediaJobs.providerLimits(capability).maxRuntime();
    }

    @Override
    public Duration queueTimeout(Duration lease) {
        return mediaJobs.providerLimits(capability).queueTimeout();
    }

    @Override
    public String execute(StepContext context) {
        Map<String, Object> payload = mappedPayload(payload(context.input()), context.config());
        Map<String, Object> params = new LinkedHashMap<>();
        String jobProfile = profile(context.config());
        params.put("profile", jobProfile);
        params.put("payload", payload);
        ProviderJob job = new ProviderJob("1", context.stepId(), context.runId(), context.runId(),
                context.stepOrder(), capability, params, providerInputs(context, payload),
                MediaJob.outputPrefix(context.runId(), context.stepOrder(), context.attempt()),
                (int) context.attempt(), Math.toIntExact(maxRuntime().toSeconds()), clock.instant(),
                vendorLedger(context, jobProfile, payload));
        try {
            mediaJobs.enqueueProvider(job);
        } catch (ConstraintViolationException e) {
            throw new StepFailedException("contract_violation",
                    "The provider job breaks its contract: " + e.getMessage(), e);
        }
        return null;
    }

    /**
     * The attach candidates for this request, claimed inside the dispatching transaction. A ledger
     * that cannot answer must not stop a paid step, so it dispatches with none.
     */
    private VendorLedger vendorLedger(StepContext context, String jobProfile, Map<String, Object> payload) {
        if (ledger == null || (!"generate_video".equals(capability)
                && !"generate_keyframe".equals(capability)
                && !"lipsync_video".equals(capability))) {
            return null;
        }
        String requestKey = VendorRequestKey.of(capability, jobProfile, payload);
        List<VendorLedger.Candidate> candidates = List.of();
        if (vendorJobs.resumeEnabled()) {
            try {
                candidates = ledger.claimCandidates(context.runId(), context.stepId(), capability, requestKey);
            } catch (DataAccessException e) {
                LOG.warn("vendor_job outcome=ledger_error op=claim run={} step={}", context.runId(),
                        context.stepId(), e);
            }
        }
        return new VendorLedger(requestKey, candidates);
    }

    /**
     * Resolves product image input. If the step config defines referenceImage, only that named
     * image from the run's inputs is sent. Otherwise, for capabilities binding run inputs (script,
     * storyboard), the first snapshotted product image is sent as visual ground truth.
     */
    private List<ProviderInput> providerInputs(StepContext context, Map<String, Object> payload) {
        if ("lipsync_video".equals(capability)) {
            return resolveLipsyncInputs(context);
        }
        if (!bindsRunInputs()) {
            return List.of();
        }
        if (context.config() != null && !context.config().isBlank()) {
            JsonNode tree;
            try {
                tree = objectMapper.readTree(context.config());
            } catch (JsonProcessingException e) {
                throw new StepFailedException("contract_violation", "Provider step config is not valid JSON", e);
            }
            JsonNode references = tree.get("referenceImages");
            if (references != null && !references.isNull()) {
                Object rawFirstFrame = payload != null ? payload.get("firstFrameKey") : null;
                String firstFrameKey = rawFirstFrame instanceof String s && !s.isBlank() ? s : null;
                return resolveMultipleReferences(context, references, firstFrameKey);
            }
            JsonNode reference = tree.get("referenceImage");
            if (reference != null && !reference.isNull()) {
                if (!reference.isTextual() || reference.textValue().isBlank()) {
                    throw new StepFailedException("contract_violation", "referenceImage must name a run input");
                }
                String key = reference.textValue();
                String runInputs = "runs/" + context.runId() + "/inputs/";
                return storedInputs(context.storedInput(), key).stream()
                        .filter(input -> key.equals(input.key()) && input.bucket() != null && !input.bucket().isBlank()
                                && input.objectKey() != null && MediaJob.isBelow(runInputs, input.objectKey()))
                        .findFirst()
                        .map(input -> List.of(new ProviderInput(input.key(), input.bucket(), input.objectKey())))
                        .orElseThrow(() -> missingImage(key, null));
            }
        }
        if (context.storedInput() != null && !context.storedInput().isBlank()) {
            try {
                List<MediaJobInput> inputs = objectMapper.readValue(context.storedInput(), INPUTS);
                if (!inputs.isEmpty()) {
                    if ("generate_storyboard".equals(capability)) {
                        return inputs.stream()
                                .filter(input -> input.key() != null
                                        && (input.key().startsWith("image_") || "background".equals(input.key())))
                                .limit(4)
                                .map(input -> new ProviderInput(input.key(), input.bucket(), input.objectKey()))
                                .toList();
                    }
                    MediaJobInput first = inputs.getFirst();
                    return List.of(new ProviderInput(first.key(), first.bucket(), first.objectKey()));
                }
            } catch (JsonProcessingException e) {
                throw new StepFailedException("contract_violation",
                        "Provider image input is not a valid ordered list", e);
            }
        }
        return List.of();
    }

    private List<ProviderInput> resolveMultipleReferences(StepContext context, JsonNode references,
            String firstFrameKey) {
        String runScope = "runs/" + context.runId() + "/";
        List<MediaJobInput> available = storedInputs(context.storedInput(), "referenceImages").stream()
                .filter(input -> input.bucket() != null && !input.bucket().isBlank()
                        && input.objectKey() != null && MediaJob.isBelow(runScope, input.objectKey()))
                .toList();
        int maxRefs = "generate_keyframe".equals(capability)
                ? 5
                : "generate_video".equals(capability) ? 4 : 3;
        if (references.isTextual() && "all".equals(references.textValue())) {
            List<ProviderInput> resolved;
            if (firstFrameKey != null) {
                ProviderInput requiredFirst = available.stream()
                        .filter(input -> firstFrameKey.equals(input.key()))
                        .findFirst()
                        .map(input -> new ProviderInput(input.key(), input.bucket(), input.objectKey()))
                        .orElseThrow(() -> missingImage(firstFrameKey, null));
                List<ProviderInput> combined = new java.util.ArrayList<>(maxRefs);
                combined.add(requiredFirst);
                available.stream()
                        .filter(input -> !firstFrameKey.equals(input.key()))
                        .limit(Math.max(0, maxRefs - 1))
                        .map(input -> new ProviderInput(input.key(), input.bucket(), input.objectKey()))
                        .forEach(combined::add);
                resolved = List.copyOf(combined);
            } else {
                resolved = available.stream()
                        .limit(maxRefs)
                        .map(input -> new ProviderInput(input.key(), input.bucket(), input.objectKey()))
                        .toList();
            }
            if (resolved.isEmpty()) {
                throw missingImage("referenceImages", null);
            }
            return resolved;
        }
        if (references.isArray() && !references.isEmpty() && references.size() <= maxRefs) {
            List<ProviderInput> resolved = new java.util.ArrayList<>(references.size());
            for (JsonNode entry : references) {
                if (!entry.isTextual() || entry.textValue().isBlank()) {
                    throw new StepFailedException("contract_violation",
                            "referenceImages entries must name run inputs");
                }
                String key = entry.textValue();
                resolved.add(available.stream()
                        .filter(input -> key.equals(input.key()))
                        .findFirst()
                        .map(input -> new ProviderInput(input.key(), input.bucket(), input.objectKey()))
                        .orElseThrow(() -> missingImage(key, null)));
            }
            return List.copyOf(resolved);
        }
        throw new StepFailedException("contract_violation",
                "referenceImages must be 'all' or an array of 1 to 3 input keys");
    }

    private boolean isLipsyncArrayInput(String input) {
        if (!"lipsync_video".equals(capability) || input == null || input.isBlank()) {
            return false;
        }
        try {
            return objectMapper.readTree(input).isArray();
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    private List<ProviderInput> resolveLipsyncInputs(StepContext context) {
        String runScope = "runs/" + context.runId() + "/";
        if (isLipsyncArrayInput(context.storedInput())) {
            List<MediaJobInput> entries = storedInputs(context.storedInput(), "video/audio");
            return List.of(
                    findScopedInput(entries, "video", runScope),
                    findScopedInput(entries, "audio", runScope));
        }
        if (isLipsyncArrayInput(context.input())) {
            List<MediaJobInput> entries = storedInputs(context.input(), "video/audio");
            return List.of(
                    findScopedInput(entries, "video", runScope),
                    findScopedInput(entries, "audio", runScope));
        }
        Map<String, Object> input = payload(context.input());
        return List.of(
                extractAssetInput(context.runId(), input, "video"),
                extractAssetInput(context.runId(), input, "audio"));
    }

    private ProviderInput findScopedInput(List<MediaJobInput> entries, String key, String runScope) {
        return entries.stream()
                .filter(entry -> key.equals(entry.key())
                        && entry.bucket() != null && !entry.bucket().isBlank()
                        && entry.objectKey() != null && MediaJob.isBelow(runScope, entry.objectKey()))
                .findFirst()
                .map(entry -> new ProviderInput(entry.key(), entry.bucket(), entry.objectKey()))
                .orElseThrow(() -> new StepFailedException("contract_violation",
                        "lipsync_video requires '" + key + "' outputInput inside the current run"));
    }

    private ProviderInput extractAssetInput(UUID runId, Map<String, Object> input, String key) {
        Object raw = input.get(key);
        if (!(raw instanceof Map<?, ?> ref)) {
            throw new StepFailedException("contract_violation",
                    "lipsync_video requires '" + key + "' asset reference with bucket and objectKey");
        }
        Object bucket = ref.get("bucket");
        Object objectKey = ref.get("objectKey");
        if (!(bucket instanceof String b) || b.isBlank() || !(objectKey instanceof String k) || k.isBlank()) {
            throw new StepFailedException("contract_violation",
                    "lipsync_video '" + key + "' reference must include non-blank bucket and objectKey");
        }
        String runScope = "runs/" + runId + "/";
        if (!MediaJob.isBelow(runScope, k)) {
            throw new StepFailedException("contract_violation",
                    "lipsync_video '" + key + "' objectKey must be inside the current run");
        }
        return new ProviderInput(key, b, k);
    }

    private List<MediaJobInput> storedInputs(String storedInput, String key) {
        if (storedInput == null || storedInput.isBlank()) {
            throw missingImage(key, null);
        }
        try {
            return objectMapper.readValue(storedInput, INPUTS);
        } catch (JsonProcessingException e) {
            throw missingImage(key, e);
        }
    }

    private static StepFailedException missingImage(String key, Throwable cause) {
        return new StepFailedException("contract_violation",
                "Product image " + key + " is missing from the run inputs", cause);
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
            Map<String, Object> parsedConfig = objectMapper.readValue(config, OBJECT);
            Object mapping = parsedConfig.get("payloadFields");
            Map<String, Object> result;
            if (mapping == null) {
                result = new LinkedHashMap<>(input);
            } else {
                if (!(mapping instanceof Map<?, ?> fields) || fields.isEmpty()) {
                    throw new StepFailedException("contract_violation",
                            "Provider payloadFields must be a non-empty object");
                }
                result = new LinkedHashMap<>();
                for (Map.Entry<?, ?> field : fields.entrySet()) {
                    if (!(field.getKey() instanceof String target) || target.isBlank()
                            || !(field.getValue() instanceof String source) || source.isBlank()
                            || !input.containsKey(source)) {
                        throw new StepFailedException("contract_violation",
                                "Provider payloadFields contains an invalid or missing source field");
                    }
                    result.put(target, input.get(source));
                }
            }
            if (parsedConfig.get("payloadDefaults") instanceof Map<?, ?> defaults) {
                for (Map.Entry<?, ?> entry : defaults.entrySet()) {
                    if (entry.getKey() instanceof String key && !key.isBlank() && entry.getValue() != null) {
                        result.putIfAbsent(key, entry.getValue());
                    }
                }
            }
            appendPromptInstruction(result, parsedConfig);
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
