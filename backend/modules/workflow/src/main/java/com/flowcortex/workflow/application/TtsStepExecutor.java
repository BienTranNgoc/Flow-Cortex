package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.media.api.MediaStoreUnavailableException;
import com.flowcortex.workflow.application.provider.DubProperties;
import com.flowcortex.workflow.application.provider.GeminiClient;
import com.flowcortex.workflow.application.provider.GeminiException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@code tts} — voices every non-empty reviewed line, in segment order, in one request with the
 * run's voice, and stores {@value #VOICE_FILE} plus {@value #MANIFEST} listing the lines voiced.
 *
 * <p>A line the member did not change that would take longer than the configured multiple of its
 * slot is first reworded shorter — one translation request for all such lines; a line the member
 * changed is voiced as written. The manifest records the text actually voiced.
 */
@Component
class TtsStepExecutor implements StepExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(TtsStepExecutor.class);
    static final String TYPE = "tts";
    static final String VOICE_FILE = "voice.wav";
    static final String MANIFEST = "voice-manifest.json";
    /** The speech model's inline tag between two lines. */
    private static final String PAUSE = " <long pause> ";
    private static final Pattern INLINE_TAG = Pattern.compile("\\s*<[^<>]*>\\s*");
    /** Reading the input and storing both files, on top of the provider's worst case. */
    private static final Duration MARGIN = Duration.ofMinutes(1);

    private final ObjectMapper json = new ObjectMapper();
    private final GeminiClient gemini;
    private final MediaJobs mediaJobs;
    private final DubProperties dub;

    TtsStepExecutor(GeminiClient gemini, MediaJobs mediaJobs, DubProperties dub) {
        this.gemini = gemini;
        this.mediaJobs = mediaJobs;
        this.dub = dub;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Mode mode() {
        return Mode.INLINE;
    }

    /** Above the shortening request and the voice request both making every call they may make. */
    @Override
    public Duration maxRuntime() {
        DubProperties.Gemini gemini = dub.gemini();
        return gemini.worstCase(gemini.translateModels().size()).plus(gemini.speechWorstCase()).plus(MARGIN);
    }

    @Override
    public boolean available() {
        return dub.available();
    }

    @Override
    public String execute(StepContext context) {
        if (!available()) {
            throw DubSegments.dubDisabled();
        }
        List<DubSegments.Segment> segments = DubSegments.read(json, context.input());
        for (DubSegments.Segment segment : segments) {
            if (segment.vi() == null) {
                throw DubSegments.contractViolation("Segment " + segment.index() + " has no Vietnamese line");
            }
        }
        List<DubSegments.Segment> voiced = segments.stream()
                .map(segment -> new DubSegments.Segment(segment.index(), segment.startMs(), segment.endMs(),
                        segment.zh(), withoutTags(segment.vi()), segment.edited()))
                .filter(segment -> !segment.vi().isBlank())
                .sorted(Comparator.comparingInt(DubSegments.Segment::index))
                .toList();
        String voice = dub.voices().get(context.options().voice());
        if (voice == null) {
            throw DubSegments.contractViolation("The run's voice has no configured voice name");
        }
        Map<Integer, String> text = voiced.stream().collect(Collectors.toMap(DubSegments.Segment::index,
                DubSegments.Segment::vi, (a, b) -> a, LinkedHashMap::new));
        shortenOverruns(voiced, text);

        String model = null;
        if (!voiced.isEmpty()) {
            GeminiClient.Speech speech;
            try {
                speech = gemini.speak(String.join(PAUSE, text.values()), voice);
            } catch (GeminiException e) {
                throw DubSegments.providerFailure(e);
            }
            model = speech.model();
            store(context, VOICE_FILE, "audio/wav", speech.wav());
        }
        store(context, MANIFEST, "application/json", manifest(voice, model, voiced, text));
        return "{\"voiced\":" + voiced.size() + "}";
    }

    /** Rewords, in one request, every unedited line expected to overrun its slot; keeps a line the answer did not shorten. */
    private void shortenOverruns(List<DubSegments.Segment> voiced, Map<Integer, String> text) {
        List<DubSegments.Segment> overruns = voiced.stream()
                .filter(segment -> !segment.edited())
                .filter(segment -> DubSegments.length(segment.vi()) / dub.charsPerSecond()
                        > dub.shortenAbove() * segment.slotMs() / 1000.0)
                .toList();
        if (overruns.isEmpty()) {
            return;
        }
        StringBuilder prompt = new StringBuilder("Reword each numbered Vietnamese line of a dubbed short video "
                + "shorter, keeping its meaning, so it can be spoken within the line's time. Answer with one entry "
                + "per line, with the same index. Each line must have at most the given number of characters.\n");
        for (DubSegments.Segment segment : overruns) {
            prompt.append('\n').append(segment.index()).append(" (max ")
                    .append(DubSegments.limit(dub.charsPerSecond(), segment)).append(" characters): ")
                    .append(segment.vi());
        }
        Map<Integer, String> answer;
        try {
            answer = DubSegments.lines(json, gemini.generateJson(prompt.toString(), DubSegments.LINES_SCHEMA));
        } catch (GeminiException e) {
            throw DubSegments.providerFailure(e);
        }
        int shortened = 0;
        for (DubSegments.Segment segment : overruns) {
            String shorter = answer == null || answer.get(segment.index()) == null ? null
                    : withoutTags(answer.get(segment.index()));
            if (shorter != null && !shorter.isBlank()
                    && DubSegments.length(shorter) < DubSegments.length(segment.vi())) {
                text.put(segment.index(), shorter);
                shortened++;
            }
        }
        LOG.info("Overrunning lines reworded before voicing: {} of {}", shortened, overruns.size());
    }

    /** The speech model reads {@code <…>} as a delivery tag, so a line keeps none: only {@link #PAUSE} joins lines. */
    private static String withoutTags(String line) {
        return INLINE_TAG.matcher(line).replaceAll(" ").strip();
    }

    private byte[] manifest(String voice, String model, List<DubSegments.Segment> voiced, Map<Integer, String> text) {
        ObjectNode manifest = json.createObjectNode();
        manifest.put("voice", voice);
        manifest.put("model", model);
        ArrayNode segments = manifest.putArray("segments");
        for (DubSegments.Segment segment : voiced) {
            segments.addObject()
                    .put("index", segment.index())
                    .put("startMs", segment.startMs())
                    .put("endMs", segment.endMs())
                    .put("vi", text.get(segment.index()));
        }
        try {
            return json.writeValueAsString(manifest).getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("The voice manifest could not be serialized");
        }
    }

    private void store(StepContext context, String name, String mime, byte[] bytes) {
        try {
            context.store(mediaJobs, name, mime, bytes);
        } catch (MediaStoreUnavailableException e) {
            LOG.warn("Could not store {} for step {}", name, context.stepOrder(), e);
            throw new StepFailedException("store_unavailable", "Couldn't save the step's files");
        }
    }
}
