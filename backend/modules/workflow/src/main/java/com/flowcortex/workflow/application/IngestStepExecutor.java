package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.media.api.MediaJobInput;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.media.api.MediaOutput;
import com.flowcortex.media.api.MediaStoreUnavailableException;
import com.flowcortex.media.api.RunInput;
import com.flowcortex.media.api.UploadRejectedException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An ingest step: copies the one uploaded file of its kind into the step's own prefix and records it as
 * output {@code outputName}, so later steps read it through {@code outputInputs} like a worker's output.
 * Runs inline; the copy is server-side, nothing streams through the application.
 */
class IngestStepExecutor implements StepExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(IngestStepExecutor.class);
    static final Set<String> TYPES = Set.of("ingest_video", "ingest_image");
    private static final TypeReference<List<MediaJobInput>> INPUTS = new TypeReference<>() { };

    private final ObjectMapper json = new ObjectMapper();
    private final String type;
    private final String kindPrefix;
    private final String outputName;
    private final MediaJobs mediaJobs;

    IngestStepExecutor(String type, String kindPrefix, String outputName, MediaJobs mediaJobs) {
        this.type = type;
        this.kindPrefix = kindPrefix;
        this.outputName = outputName;
        this.mediaJobs = mediaJobs;
    }

    @Override
    public String type() {
        return type;
    }

    @Override
    public Mode mode() {
        return Mode.INLINE;
    }

    @Override
    public InputPolicy inputPolicy() {
        return InputPolicy.STORED;
    }

    @Override
    public Duration maxRuntime() {
        return Duration.ofSeconds(60);
    }

    @Override
    public String execute(StepContext context) {
        List<MediaJobInput> stored = storedInputs(context.storedInput());
        Map<String, String> mimeByKey = stored.isEmpty() ? Map.of() : mediaJobs.runInputs(context.runId()).stream()
                .collect(Collectors.toMap(RunInput::bindingKey, RunInput::mimeType, (first, second) -> first));
        List<MediaJobInput> ofKind = stored.stream()
                .filter(input -> {
                    String mime = mimeByKey.get(input.key());
                    return mime != null && mime.startsWith(kindPrefix);
                })
                .toList();
        if (ofKind.size() != 1) {
            throw rejected("This step needs exactly one uploaded " + outputName);
        }
        MediaOutput copy;
        try {
            copy = mediaJobs.copyRunObject(context.runId(), context.stepOrder(), ofKind.getFirst(), outputName);
        } catch (UploadRejectedException e) {
            LOG.info("Upload gone before step {} copied it: {}", context.stepOrder(), e.getMessage());
            throw rejected("The uploaded file is no longer available");
        } catch (MediaStoreUnavailableException e) {
            LOG.warn("Could not copy {} for step {}", outputName, context.stepOrder(), e);
            throw new StepFailedException("store_unavailable", "Couldn't save the step's files");
        }
        context.storedOutputs().add(copy);
        return json.createObjectNode().put("mime", copy.mime()).put("bytes", copy.bytes()).toString();
    }

    private List<MediaJobInput> storedInputs(String storedInput) {
        if (storedInput == null || storedInput.isBlank()) {
            return List.of();
        }
        try {
            JsonNode tree = json.readTree(storedInput);
            if (tree.isArray()) {
                return json.convertValue(tree, INPUTS);
            }
            if (tree.isObject()) {
                return List.of(json.treeToValue(tree, MediaJobInput.class));
            }
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw notReferences(e);
        }
        throw notReferences(null);
    }

    private static StepFailedException notReferences(Exception cause) {
        return new StepFailedException("contract_violation",
                "The step input is not an object reference or an ordered list of object references", cause);
    }

    private static StepFailedException rejected(String message) {
        return new StepFailedException("input_rejected", message);
    }
}
