package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.IntFunction;

/**
 * The file names each step type records as outputs, which {@code outputInputs} entries of later steps may take. An
 * empty set is a declaration that the type records no files; a type that is not listed is undeclared and is never
 * checked. Held equal to {@code contracts/step-file-outputs.json}, which the worker tests compare with the names its
 * handlers write.
 */
final class StepFileOutputs {

    private static final Map<String, Set<String>> DECLARED = declarations();
    private static final ObjectMapper READER = new ObjectMapper();

    private StepFileOutputs() {
    }

    /** The files {@code type} records, or empty when the type has no declaration. */
    static Optional<Set<String>> declared(String type) {
        return Optional.ofNullable(DECLARED.get(type));
    }

    /** Every declaration, types and names sorted. */
    static Map<String, Set<String>> all() {
        return DECLARED;
    }

    /** An entry taking {@code file} from step {@code sourceOrder}, whose type records only {@code recorded}. */
    record Undeclared(String file, int sourceOrder, Set<String> recorded) {
    }

    /**
     * The first {@code outputInputs} entry of {@code config}, optional or not, that names a file its source step's
     * type declares it does not record. {@code recordedAt} gives the declared files of the step at an order, or
     * {@code null} when that step is undeclared or not known, so its entries are not checked. A config that is
     * not a JSON object, and an entry that is not well-formed, are left to the declaration rules.
     */
    static Optional<Undeclared> firstUndeclared(String config, IntFunction<Set<String>> recordedAt) {
        if (config == null || config.isBlank()) {
            return Optional.empty();
        }
        JsonNode mappings;
        try {
            JsonNode root = READER.readTree(config);
            mappings = root == null || !root.isObject() ? null : root.get("outputInputs");
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
        if (mappings == null || !mappings.isArray()) {
            return Optional.empty();
        }
        for (JsonNode mapping : mappings) {
            JsonNode order = mapping.get("stepOrder");
            JsonNode name = mapping.get("outputName");
            if (order == null || !order.isIntegralNumber() || !order.canConvertToInt() || order.intValue() < 1
                    || name == null || !name.isTextual()) {
                continue;
            }
            Set<String> recorded = recordedAt.apply(order.intValue());
            if (recorded != null && !recorded.contains(name.textValue())) {
                return Optional.of(new Undeclared(name.textValue(), order.intValue(), recorded));
            }
        }
        return Optional.empty();
    }

    private static Map<String, Set<String>> declarations() {
        Map<String, Set<String>> types = new TreeMap<>();
        types.put(HumanSteps.APPROVAL_CHECKPOINT, Set.of());
        types.put(HumanSteps.QA_REVIEW, Set.of());
        types.put("align_audio", Set.of("aligned-speech.wav", "alignment-manifest.json"));
        types.put("caption_burn", Set.of("preview.png", "video.mp4"));
        types.put("echo", Set.of());
        types.put("extract_audio", Set.of("source-audio.wav"));
        types.put("fail", Set.of());
        types.put("fetch_video", Set.of("audio", "video"));
        types.put("generate_script", Set.of());
        types.put("generate_speech", Set.of("manifest.json", "speech.wav"));
        types.put("generate_storyboard", Set.of());
        types.put("generate_video", Set.of("clip.mp4", "manifest.json"));
        types.put("probe", Set.of("video-details.json"));
        types.put("render_video", Set.of("manifest.json", "video.mp4"));
        types.put("translate", Set.of());
        types.put("tts", Set.of("voice-manifest.json", "voice.wav"));
        types.replaceAll((type, names) -> Collections.unmodifiableSet(new TreeSet<>(names)));
        return Collections.unmodifiableMap(types);
    }
}
