package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reading the list an iterate step runs over and rendering a child's config for one item. Nothing
 * here puts an item's content into an exception message: those messages reach the run's error.
 */
final class IterateItems {

    /** Fails on text after the first JSON value, so {@code 1 apple} is text and {@code [1] x} is no list. */
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final String FENCE_OPEN = "```json";
    private static final String FENCE_CLOSE = "```";
    private static final Pattern TOKEN = Pattern.compile("\\{(item(?:\\.[^.{}\\s]+)*|index)\\}");

    sealed interface Classified permits Items, NotAList {
    }

    /** The kept elements as step inputs, and how many past {@code maxItems} were cut. */
    record Items(List<String> inputs, int dropped) implements Classified {
    }

    record NotAList() implements Classified {
    }

    private IterateItems() {
    }

    /**
     * Reads {@code input} as a list: a JSON array, such an array in text optionally inside a
     * {@code json} fence, or an object with exactly one array-valued field. An element is a step input as
     * text when it is text, else as compact JSON.
     */
    static Classified classify(String input, int maxItems) {
        JsonNode list = listOf(input);
        if (list == null) {
            return new NotAList();
        }
        int kept = Math.min(list.size(), maxItems);
        List<String> inputs = new ArrayList<>(kept);
        for (int i = 0; i < kept; i++) {
            JsonNode element = list.get(i);
            inputs.add(element.isTextual() ? element.textValue() : element.toString());
        }
        return new Items(List.copyOf(inputs), list.size() - kept);
    }

    /**
     * Whether element {@code index} of {@code listInput} is a JSON string whose text is {@code item}. A
     * string element is stored as its bare text, so only the list can tell {@code "42"} from {@code 42}.
     */
    static boolean isTextItem(String listInput, int index, String item) {
        JsonNode list = listOf(listInput);
        if (list == null || index < 0 || index >= list.size()) {
            return false;
        }
        JsonNode element = list.get(index);
        return element.isTextual() && element.textValue().equals(item);
    }

    /**
     * {@code childConfig} with every string value that is exactly {@code {item}}, {@code {item.a.b}} or
     * {@code {index}} replaced by that value with its JSON type, and each such token inside a longer
     * string replaced by its text (objects and arrays as JSON, null as empty text). An item that is not
     * {@code textItem} and reads as JSON keeps that type; a {@code textItem} is always a string.
     *
     * @throws StepFailedException {@code iterate_item_field_missing} when a token names a field the item lacks
     */
    static String render(String childConfig, String item, boolean textItem, int index) {
        JsonNode config = parse(childConfig == null || childConfig.isBlank() ? "{}" : childConfig);
        if (config == null || !config.isObject()) {
            throw new StepFailedException("iterate_config_invalid", "The child config is not a JSON object");
        }
        JsonNode value = textItem ? JsonNodeFactory.instance.textNode(item == null ? "" : item) : itemValue(item);
        return rendered(config, value, index).toString();
    }

    /** The array a step input stands for, or {@code null} when the input is no list. */
    private static JsonNode listOf(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String text = input.strip();
        boolean fenced = text.startsWith(FENCE_OPEN);
        if (fenced) {
            if (!text.endsWith(FENCE_CLOSE) || text.length() < FENCE_OPEN.length() + FENCE_CLOSE.length()) {
                return null;
            }
            text = text.substring(FENCE_OPEN.length(), text.length() - FENCE_CLOSE.length()).strip();
        }
        JsonNode root = parse(text);
        JsonNode list = fenced ? root : arrayOf(root);
        return list != null && list.isArray() ? list : null;
    }

    private static JsonNode arrayOf(JsonNode root) {
        if (root == null) {
            return null;
        }
        if (root.isArray()) {
            return root;
        }
        if (!root.isObject()) {
            return null;
        }
        JsonNode found = null;
        for (Iterator<JsonNode> values = root.elements(); values.hasNext();) {
            JsonNode value = values.next();
            if (value.isArray()) {
                if (found != null) {
                    return null;
                }
                found = value;
            }
        }
        return found;
    }

    private static JsonNode parse(String text) {
        try {
            return JSON.readTree(text);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /** An item that reads as a JSON object, array, number, boolean or null keeps that type; any other is text. */
    private static JsonNode itemValue(String item) {
        String text = item == null ? "" : item;
        JsonNode parsed = parse(text);
        if (parsed == null || parsed.isMissingNode() || parsed.isTextual()) {
            return JsonNodeFactory.instance.textNode(text);
        }
        return parsed;
    }

    private static JsonNode rendered(JsonNode node, JsonNode item, int index) {
        if (node.isTextual()) {
            return renderedText(node.textValue(), item, index);
        }
        if (node.isObject()) {
            ObjectNode copy = JsonNodeFactory.instance.objectNode();
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                copy.set(field.getKey(), rendered(field.getValue(), item, index));
            }
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = JsonNodeFactory.instance.arrayNode();
            node.forEach(element -> copy.add(rendered(element, item, index)));
            return copy;
        }
        return node;
    }

    private static JsonNode renderedText(String text, JsonNode item, int index) {
        Matcher whole = TOKEN.matcher(text);
        if (whole.matches()) {
            return value(whole.group(1), item, index);
        }
        Matcher tokens = TOKEN.matcher(text);
        StringBuilder out = new StringBuilder();
        while (tokens.find()) {
            tokens.appendReplacement(out, Matcher.quoteReplacement(asText(value(tokens.group(1), item, index))));
        }
        tokens.appendTail(out);
        return JsonNodeFactory.instance.textNode(out.toString());
    }

    private static JsonNode value(String token, JsonNode item, int index) {
        if ("index".equals(token)) {
            return JsonNodeFactory.instance.numberNode(index);
        }
        JsonNode current = item;
        if (token.length() > "item".length()) {
            for (String field : token.substring("item.".length()).split("\\.")) {
                current = current.isObject() ? current.get(field) : null;
                if (current == null) {
                    throw new StepFailedException("iterate_item_field_missing",
                            "The item has no field '" + token.substring("item.".length()) + "'");
                }
            }
        }
        return current;
    }

    private static String asText(JsonNode value) {
        if (value.isNull()) {
            return "";
        }
        return value.isContainerNode() ? value.toString() : value.asText();
    }
}
