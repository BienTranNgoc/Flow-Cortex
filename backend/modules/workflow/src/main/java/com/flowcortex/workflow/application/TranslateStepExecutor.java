package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowcortex.workflow.application.provider.DubProperties;
import com.flowcortex.workflow.application.provider.GeminiClient;
import com.flowcortex.workflow.application.provider.GeminiException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@code translate} — turns the transcript's segments into one Vietnamese line each, every line within
 * the characters its slot can be read in. An answer with a wrong line count or a line over its limit
 * is asked for once more, naming the offending lines; a second such answer fails the step
 * {@code translation_invalid}.
 */
@Component
class TranslateStepExecutor implements StepExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(TranslateStepExecutor.class);
    static final String TYPE = "translate";
    /** The first ask and the one re-ask. */
    private static final int ASKS = 2;
    /** Reading the input and writing the output, on top of the provider's worst case. */
    private static final Duration MARGIN = Duration.ofMinutes(1);

    private final ObjectMapper json = new ObjectMapper();
    private final GeminiClient gemini;
    private final DubProperties dub;

    TranslateStepExecutor(GeminiClient gemini, DubProperties dub) {
        this.gemini = gemini;
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

    /** Above both asks taking every attempt on every translation model, so a provider failure keeps its own code. */
    @Override
    public Duration maxRuntime() {
        return dub.gemini().worstCase(dub.gemini().translateModels().size()).multipliedBy(ASKS).plus(MARGIN);
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
            if (segment.zh() == null) {
                throw DubSegments.contractViolation("Segment " + segment.index() + " has no Chinese line");
            }
        }
        if (segments.isEmpty()) {
            return output(segments, Map.of());
        }
        String prompt = prompt(segments);
        Map<Integer, String> lines = ask(prompt);
        List<String> problems = problems(segments, lines);
        if (!problems.isEmpty()) {
            LOG.info("Translation answer not accepted, asking once more: {}", problems);
            lines = ask(prompt + "\n\nYour previous answer was not accepted:\n" + String.join("\n", problems)
                    + "\nAnswer again with every line.");
            problems = problems(segments, lines);
            if (!problems.isEmpty()) {
                LOG.warn("Translation answer not accepted after asking again: {}", problems);
                throw new StepFailedException("translation_invalid",
                        "The translation came back incomplete. Try running again.");
            }
        }
        return output(segments, lines);
    }

    private String prompt(List<DubSegments.Segment> segments) {
        StringBuilder prompt = new StringBuilder("Translate each numbered line of this Chinese short-video dialogue "
                + "into natural spoken Vietnamese. Answer with one entry per line, with the same index. Each "
                + "Vietnamese line must have at most the given number of characters, so it can be spoken within "
                + "the line's time.\n");
        for (DubSegments.Segment segment : segments) {
            prompt.append('\n').append(segment.index()).append(" (max ")
                    .append(DubSegments.limit(dub.charsPerSecond(), segment)).append(" characters): ")
                    .append(segment.zh());
        }
        return prompt.toString();
    }

    private Map<Integer, String> ask(String prompt) {
        try {
            return DubSegments.lines(json, gemini.generateJson(prompt, DubSegments.LINES_SCHEMA));
        } catch (GeminiException e) {
            throw DubSegments.providerFailure(e);
        }
    }

    /** Why the answer cannot be used, one entry per offending line; empty when it can. Never quotes the text. */
    private List<String> problems(List<DubSegments.Segment> segments, Map<Integer, String> lines) {
        if (lines == null) {
            return List.of("the answer was not a list of {index, vi} entries");
        }
        List<String> problems = new ArrayList<>();
        if (lines.size() != segments.size()) {
            problems.add("expected " + segments.size() + " lines, got " + lines.size());
        }
        Set<Integer> indexes = new HashSet<>();
        for (DubSegments.Segment segment : segments) {
            indexes.add(segment.index());
            String line = lines.get(segment.index());
            int limit = DubSegments.limit(dub.charsPerSecond(), segment);
            if (line == null) {
                problems.add("line " + segment.index() + ": missing");
            } else if (line.isBlank()) {
                problems.add("line " + segment.index() + ": empty");
            } else if (DubSegments.length(line) > limit) {
                problems.add("line " + segment.index() + ": " + DubSegments.length(line) + " characters, at most "
                        + limit);
            }
        }
        lines.keySet().stream().filter(index -> !indexes.contains(index)).sorted()
                .forEach(index -> problems.add("line " + index + ": no such line"));
        return problems;
    }

    private String output(List<DubSegments.Segment> segments, Map<Integer, String> lines) {
        ObjectNode document = json.createObjectNode();
        ArrayNode list = document.putArray("segments");
        for (DubSegments.Segment segment : segments) {
            list.addObject()
                    .put("index", segment.index())
                    .put("startMs", segment.startMs())
                    .put("endMs", segment.endMs())
                    .put("zh", segment.zh())
                    .put("vi", lines.get(segment.index()));
        }
        try {
            return json.writeValueAsString(document);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("The translated segments could not be serialized");
        }
    }
}
