package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.text.Normalizer;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Writes and strictly reads the portable workflow artifact (format 1). The reader checks every object for its exact
 * key set and every value against its bound, and reports all faults in one pass, each at its path
 * ({@code steps[0].ownerId}); a duplicate key is a fault at its own path rather than a silent last-wins.
 * String lengths count code points, as the database counts characters.
 */
public final class WorkflowArtifacts {

    public static final int FORMAT_VERSION = 1;
    public static final int MAX_STEPS = 32_767;
    public static final int MAX_NAME = 255;
    public static final int MAX_TYPE = 50;
    public static final int MAX_INPUT_REF = 255;
    public static final int MAX_CONFIG_CHARS = 50_000;
    private static final int MAX_FILENAME = 80;

    /** The machine-readable reason of a fault; each has a message in the module bundle. */
    public static final class Reason {
        public static final String NOT_JSON = "not_json";
        public static final String NOT_OBJECT = "not_object";
        public static final String DUPLICATE_KEY = "duplicate_key";
        public static final String UNKNOWN_KEY = "unknown_key";
        public static final String MISSING_KEY = "missing_key";
        public static final String WRONG_TYPE = "wrong_type";
        public static final String UNSUPPORTED_FORMAT_VERSION = "unsupported_format_version";
        public static final String NOT_A_TIMESTAMP = "not_a_timestamp";
        public static final String BLANK = "blank";
        public static final String TOO_LONG = "too_long";
        public static final String TOO_MANY_STEPS = "too_many_steps";
        public static final String NOT_POSITION = "not_position";
        public static final String INVALID_TEXT = "invalid_text";

        private Reason() {
        }
    }

    /** The outcome of {@link #read}. */
    public sealed interface ReadResult permits Parsed, Invalid {
    }

    public record Parsed(WorkflowArtifact artifact) implements ReadResult {
    }

    /** {@code faults} maps each faulty path to its {@link Reason}; {@code $} is the document itself. */
    public record Invalid(Map<String, String> faults) implements ReadResult {
        public Invalid {
            faults = Map.copyOf(faults);
        }
    }

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final JsonFactory FACTORY = new JsonFactory();
    /** Exact decimals, so {@code 2.50} and {@code 100.0} travel as written instead of being normalised. */
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false);

    private static final Instant EARLIEST_EXPORTED_AT = Instant.parse("0000-01-01T00:00:00Z");
    private static final Instant LATEST_EXPORTED_AT = Instant.parse("9999-12-31T23:59:59.999999999Z");

    private static final Set<String> ROOT_KEYS = Set.of("formatVersion", "exportedAt", "workflow", "steps");
    private static final Set<String> WORKFLOW_KEYS = Set.of("name");

    private WorkflowArtifacts() {
    }

    public static ObjectNode write(String name, List<StepDefinition> steps, Instant exportedAt) {
        ObjectNode artifact = NODES.objectNode();
        artifact.put("formatVersion", FORMAT_VERSION);
        artifact.put("exportedAt", exportedAt.toString());
        artifact.putObject("workflow").put("name", name);
        ArrayNode stepNodes = artifact.putArray("steps");
        steps.stream().sorted(Comparator.comparingInt(StepDefinition::stepOrder)).forEach(step -> {
            ObjectNode node = stepNodes.addObject();
            node.put("stepOrder", step.stepOrder());
            node.put("name", step.name());
            node.put("type", step.type());
            node.put("inputRef", step.inputRef());
            node.set("config", configNode(step.config()));
        });
        return artifact;
    }

    public static ReadResult read(byte[] body) {
        Map<String, String> faults = new LinkedHashMap<>();
        JsonNode root;
        try (JsonParser parser = FACTORY.createParser(body)) {
            if (parser.nextToken() == null) {
                return notJson();
            }
            root = build(parser, "", faults);
            if (parser.nextToken() != null) {
                return notJson();
            }
        } catch (IOException e) {
            return notJson();
        }
        if (!root.isObject()) {
            faults.putIfAbsent("$", Reason.NOT_OBJECT);
            return new Invalid(faults);
        }
        ObjectNode document = (ObjectNode) root;
        checkKeys(document, ROOT_KEYS, "", faults);

        if (document.has("formatVersion") && !isInt(document.get("formatVersion"), FORMAT_VERSION)) {
            fault(faults, "formatVersion", Reason.UNSUPPORTED_FORMAT_VERSION);
        }
        Instant exportedAt = document.has("exportedAt") ? exportedAt(document.get("exportedAt"), faults) : null;
        String name = document.has("workflow") ? workflowName(document.get("workflow"), faults) : null;
        List<StepDefinition> steps = document.has("steps") ? steps(document.get("steps"), faults) : null;

        if (!faults.isEmpty()) {
            return new Invalid(faults);
        }
        return new Parsed(new WorkflowArtifact(FORMAT_VERSION, exportedAt, name, steps));
    }

    /** An ASCII slug for a download name: diacritics removed, {@code workflow} when nothing is left. */
    public static String filename(String name) {
        String slug = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace('đ', 'd')
                .replace('Đ', 'D')
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+", "");
        if (slug.length() > MAX_FILENAME) {
            slug = slug.substring(0, MAX_FILENAME);
        }
        slug = slug.replaceAll("-+$", "");
        return slug.isEmpty() ? "workflow" : slug;
    }

    private static ReadResult notJson() {
        return new Invalid(Map.of("$", Reason.NOT_JSON));
    }

    private static JsonNode configNode(String config) {
        if (config == null) {
            return NODES.nullNode();
        }
        try {
            return JSON.readTree(config);
        } catch (IOException e) {
            throw new IllegalStateException("workflow step config is not valid JSON", e);
        }
    }

    /** Builds the tree itself so a duplicate key is reported at its path and parsing goes on to find the rest. */
    private static JsonNode build(JsonParser parser, String path, Map<String, String> faults) throws IOException {
        JsonToken token = parser.currentToken();
        switch (token) {
            case START_OBJECT -> {
                ObjectNode object = NODES.objectNode();
                while (parser.nextToken() == JsonToken.FIELD_NAME) {
                    String key = parser.currentName();
                    parser.nextToken();
                    String childPath = path.isEmpty() ? key : path + "." + key;
                    JsonNode value = build(parser, childPath, faults);
                    if (object.has(key)) {
                        fault(faults, childPath, Reason.DUPLICATE_KEY);
                    } else {
                        object.set(key, value);
                    }
                }
                return object;
            }
            case START_ARRAY -> {
                ArrayNode array = NODES.arrayNode();
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    array.add(build(parser, path + "[" + array.size() + "]", faults));
                }
                return array;
            }
            case VALUE_STRING -> {
                return NODES.textNode(parser.getText());
            }
            case VALUE_NUMBER_INT -> {
                return switch (parser.getNumberType()) {
                    case INT -> NODES.numberNode(parser.getIntValue());
                    case LONG -> NODES.numberNode(parser.getLongValue());
                    default -> NODES.numberNode(parser.getBigIntegerValue());
                };
            }
            case VALUE_NUMBER_FLOAT -> {
                return DecimalNode.valueOf(parser.getDecimalValue());
            }
            case VALUE_TRUE -> {
                return NODES.booleanNode(true);
            }
            case VALUE_FALSE -> {
                return NODES.booleanNode(false);
            }
            case VALUE_NULL -> {
                return NODES.nullNode();
            }
            default -> throw new IOException("unexpected token " + token);
        }
    }

    private static Instant exportedAt(JsonNode node, Map<String, String> faults) {
        Instant instant = node.isTextual() ? rfc3339Instant(node.asText()) : null;
        if (instant == null) {
            fault(faults, "exportedAt", Reason.NOT_A_TIMESTAMP);
        }
        return instant;
    }

    /**
     * The instant of a timestamp whose written year and UTC instant both fall in 0000-9999 (the RFC 3339 range), else
     * {@code null}. The file is stored through {@link Instant#toString()}, which outside that range cannot always be
     * read back, so what is accepted here must be inside it.
     */
    private static Instant rfc3339Instant(String text) {
        OffsetDateTime written;
        try {
            written = OffsetDateTime.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
        Instant instant = written.toInstant();
        boolean inRange = written.getYear() >= 0 && written.getYear() <= 9999
                && !instant.isBefore(EARLIEST_EXPORTED_AT) && !instant.isAfter(LATEST_EXPORTED_AT);
        return inRange ? instant : null;
    }

    private static String workflowName(JsonNode workflow, Map<String, String> faults) {
        if (!workflow.isObject()) {
            fault(faults, "workflow", Reason.WRONG_TYPE);
            return null;
        }
        checkKeys((ObjectNode) workflow, WORKFLOW_KEYS, "workflow.", faults);
        if (!workflow.has("name")) {
            return null;
        }
        JsonNode name = workflow.get("name");
        if (!name.isTextual()) {
            fault(faults, "workflow.name", Reason.WRONG_TYPE);
            return null;
        }
        String text = name.asText();
        if (text.isBlank()) {
            fault(faults, "workflow.name", Reason.BLANK);
        } else if (tooLong(text, MAX_NAME)) {
            fault(faults, "workflow.name", Reason.TOO_LONG);
        } else if (unstorable(text)) {
            fault(faults, "workflow.name", Reason.INVALID_TEXT);
        }
        return text;
    }

    private static List<StepDefinition> steps(JsonNode node, Map<String, String> faults) {
        if (!node.isArray()) {
            fault(faults, "steps", Reason.WRONG_TYPE);
            return null;
        }
        if (node.size() > MAX_STEPS) {
            fault(faults, "steps", Reason.TOO_MANY_STEPS);
            return null;
        }
        List<StepDefinition> steps = new ArrayList<>();
        for (int i = 0; i < node.size(); i++) {
            StepDefinition step = step(node.get(i), i, faults);
            if (step != null) {
                steps.add(step);
            }
        }
        return steps;
    }

    private static StepDefinition step(JsonNode node, int index, Map<String, String> faults) {
        String prefix = "steps[" + index + "]";
        if (!node.isObject()) {
            fault(faults, prefix, Reason.WRONG_TYPE);
            return null;
        }
        int before = faults.size();
        checkKeys((ObjectNode) node, WorkflowCarry.CARRIED_STEP_FIELDS, prefix + ".", faults);

        if (node.has("stepOrder")) {
            JsonNode order = node.get("stepOrder");
            if (!order.isIntegralNumber()) {
                fault(faults, prefix + ".stepOrder", Reason.WRONG_TYPE);
            } else if (!isInt(order, index + 1)) {
                fault(faults, prefix + ".stepOrder", Reason.NOT_POSITION);
            }
        }
        String name = optionalText(node, "name", MAX_NAME, prefix, faults);
        String type = requiredText(node, "type", MAX_TYPE, prefix, faults);
        String inputRef = optionalText(node, "inputRef", MAX_INPUT_REF, prefix, faults);
        String config = node.has("config") ? config(node.get("config"), prefix + ".config", faults) : null;

        return faults.size() == before ? new StepDefinition(index + 1, name, type, config, inputRef) : null;
    }

    private static String optionalText(JsonNode step, String key, int max, String prefix, Map<String, String> faults) {
        if (!step.has(key) || step.get(key).isNull()) {
            return null;
        }
        return text(step.get(key), prefix + "." + key, max, faults);
    }

    private static String requiredText(JsonNode step, String key, int max, String prefix, Map<String, String> faults) {
        if (!step.has(key)) {
            return null;
        }
        String value = text(step.get(key), prefix + "." + key, max, faults);
        if (value != null && value.isEmpty()) {
            fault(faults, prefix + "." + key, Reason.BLANK);
        }
        return value;
    }

    private static String text(JsonNode node, String path, int max, Map<String, String> faults) {
        if (!node.isTextual()) {
            fault(faults, path, Reason.WRONG_TYPE);
            return null;
        }
        String value = node.asText();
        if (tooLong(value, max)) {
            fault(faults, path, Reason.TOO_LONG);
        } else if (unstorable(value)) {
            fault(faults, path, Reason.INVALID_TEXT);
        }
        return value;
    }

    private static String config(JsonNode node, String path, Map<String, String> faults) {
        if (node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            fault(faults, path, Reason.WRONG_TYPE);
            return null;
        }
        String serialized = node.toString();
        if (serialized.length() > MAX_CONFIG_CHARS) {
            fault(faults, path, Reason.TOO_LONG);
        } else if (containsUnstorableText(node)) {
            fault(faults, path, Reason.INVALID_TEXT);
        }
        return serialized;
    }

    private static boolean containsUnstorableText(JsonNode node) {
        if (node.isTextual()) {
            return unstorable(node.asText());
        }
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                if (unstorable(field.getKey()) || containsUnstorableText(field.getValue())) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode element : node) {
                if (containsUnstorableText(element)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Reports each unknown key and each missing key of an object whose key set is exactly {@code keys}. */
    private static void checkKeys(ObjectNode object, Set<String> keys, String prefix, Map<String, String> faults) {
        object.fieldNames().forEachRemaining(key -> {
            if (!keys.contains(key)) {
                fault(faults, prefix + key, Reason.UNKNOWN_KEY);
            }
        });
        for (String key : keys) {
            if (!object.has(key)) {
                fault(faults, prefix + key, Reason.MISSING_KEY);
            }
        }
    }

    private static boolean isInt(JsonNode node, int expected) {
        return node.isIntegralNumber() && node.canConvertToInt() && node.intValue() == expected;
    }

    private static boolean tooLong(String value, int maxCodePoints) {
        return value.length() > maxCodePoints && value.codePointCount(0, value.length()) > maxCodePoints;
    }

    /** NUL and a lone surrogate cannot be stored in a text or jsonb column. */
    private static boolean unstorable(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == 0) {
                return true;
            }
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
                    return true;
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return true;
            }
        }
        return false;
    }

    private static void fault(Map<String, String> faults, String path, String reason) {
        faults.putIfAbsent(path, reason);
    }
}
