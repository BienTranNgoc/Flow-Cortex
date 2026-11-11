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
                context.stepOrder(), capability, params, providerInputs(context),
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
        if (ledger == null || !"generate_video".equals(capability)) {
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
    private List<ProviderInput> providerInputs(StepContext context) {
        if (!bindsRunInputs()) {
            return List.of();
        }
        if (context.config() != null && !context.config().isBlank()) {
            JsonNode reference;
            try {
                reference = objectMapper.readTree(context.config()).get("referenceImage");
            } catch (JsonProcessingException e) {
                throw new StepFailedException("contract_violation", "Provider step config is not valid JSON", e);
            }
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
