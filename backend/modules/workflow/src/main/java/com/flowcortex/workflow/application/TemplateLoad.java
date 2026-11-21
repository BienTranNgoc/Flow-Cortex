package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Turns a stored template and the step input into one JSON working set. Failure messages are
 * fixed sentences: they never repeat the stored template, which a caller who only chooses the
 * key must not be able to read back.
 *
 * <p>Reads with the data store's own mapper, so a number in a template reaches the step output with its digits.
 */
final class TemplateLoad {

    static final String INVALID_INPUT = "load_template_invalid_input";
    static final String NOT_FOUND = "load_template_not_found";
    static final String INVALID_TEMPLATE = "load_template_invalid_template";
    static final String MISSING_INPUT = "load_template_missing_input";

    private static final ObjectMapper JSON = DataStoreJson.mapper();

    sealed interface Result permits Loaded, Failed {}

    record Loaded(String json) implements Result {}

    record Failed(String code, String message) implements Result {}

    private TemplateLoad() {}

    /** The lookup only ever receives the key named by the input. */
    static Result load(String input, Function<String, Optional<String>> templateByKey) {
        JsonNode parsedInput = parse(input);
        if (parsedInput == null || !parsedInput.isObject()
                || !parsedInput.path("template").isTextual()
                || parsedInput.get("template").asText().isEmpty()) {
            return new Failed(INVALID_INPUT,
                    "The step input must be a JSON object with a non-empty \"template\" key.");
        }
        Optional<String> stored = templateByKey.apply(parsedInput.get("template").asText());
        if (stored.isEmpty()) {
            return new Failed(NOT_FOUND, "No template was found under the requested key.");
        }
        JsonNode template = parse(stored.get());
        if (template == null || shapeFault(template) != null) {
            return new Failed(INVALID_TEMPLATE, "The stored entry is not a valid template.");
        }
        return merge(template, parsedInput);
    }

    /** Null when {@code value} is a template; otherwise a fixed sentence saying what is wrong. */
    static String shapeFault(JsonNode value) {
        if (value == null || !value.isObject()) {
            return "A template must be a JSON object.";
        }
        if (!value.path("data").isObject()) {
            return "A template needs an object \"data\".";
        }
        JsonNode defaults = value.get("data").get("defaults");
        if (defaults != null && !defaults.isObject()) {
            return "A template's \"data.defaults\" must be an object.";
        }
        JsonNode inputs = value.get("inputs");
        if (inputs == null || inputs.isNull()) {
            return null;
        }
        if (!inputs.isObject()) {
            return "A template's \"inputs\" must be an object.";
        }
        JsonNode fields = inputs.get("fields");
        if (fields == null || fields.isNull()) {
            return null;
        }
        if (!fields.isObject()) {
            return "A template's \"inputs.fields\" must be an object.";
        }
        for (JsonNode field : fields) {
            if (!field.isObject()) {
                return "Each declared input field must be an object.";
            }
            JsonNode required = field.get("required");
            if (required != null && !required.isNull() && !required.isBoolean()) {
                return "A declared input field's \"required\" must be true or false.";
            }
        }
        return null;
    }

    private static Result merge(JsonNode template, JsonNode input) {
        ObjectNode output = ((ObjectNode) template.get("data")).deepCopy();
        JsonNode defaults = output.remove("defaults");
        JsonNode fields = template.path("inputs").path("fields");
        List<String> missing = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> declared = fields.fields();
        while (declared.hasNext()) {
            Map.Entry<String, JsonNode> field = declared.next();
            String name = field.getKey();
            JsonNode supplied = input.get(name);
            if (isFilled(supplied)) {
                output.set(name, supplied);
                continue;
            }
            if (field.getValue().path("required").asBoolean(false)) {
                missing.add(name);
            }
            JsonNode fallback = defaults == null ? null : defaults.get(name);
            output.set(name, fallback == null ? JSON.getNodeFactory().textNode("") : fallback);
        }
        if (!missing.isEmpty()) {
            return new Failed(MISSING_INPUT, "Required input missing: " + String.join(", ", missing) + ".");
        }
        return new Loaded(output.toString());
    }

    private static boolean isFilled(JsonNode value) {
        if (value == null || value.isNull()) {
            return false;
        }
        return !value.isTextual() || !value.asText().isBlank();
    }

    private static JsonNode parse(String text) {
        if (text == null) {
            return null;
        }
        try {
            return JSON.readTree(text);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
