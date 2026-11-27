package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowcortex.workflow.application.FieldRefusal.Reason;
import com.flowcortex.workflow.application.StepEditRules.FieldRule;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads, applies and checks the editable text fields of a step output. Which fields a type has comes from
 * {@link StepEditRules#EDITABLE_FIELDS}; this class holds no type test of its own. An edit replaces only strings
 * at those fields, so the next reader parses the edited output exactly as it parsed the original.
 */
final class EditedOutput {

    /** Wildcard-field element keys of the dub segment list: the line's own index and its source text. */
    private static final String INDEX_KEY = "index";
    private static final String SOURCE_KEY = "zh";

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));

    /** An editable field of a stored output; {@code index} and {@code source} are set for a list element. */
    record Field(String pointer, String label, Integer index, String source, String text) {
    }

    /** One requested change: a concrete pointer (no {@code *}) and the new text. */
    record FieldEdit(String pointer, String text) {
    }

    /** The whole edited output and the text of every editable field after the edit, in document order. */
    record Applied(String output, Map<String, String> values) {
    }

    private record Located(Field field, FieldRule rule) {
    }

    private EditedOutput() {
    }

    /** The editable fields of {@code output} in document order; empty when it lacks its type's shape. */
    static List<Field> fields(String type, String output) {
        return locate(type, output).stream().map(Located::field).toList();
    }

    /** The pointer to text map of the editable fields of {@code output}, in document order. */
    static Map<String, String> values(String type, String output) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Field field : fields(type, output)) {
            values.put(field.pointer(), field.text());
        }
        return values;
    }

    /** The field texts joined by a line feed: the text the edit percentage is measured on. */
    static String diffText(Map<String, String> values) {
        return String.join("\n", values.values());
    }

    /**
     * Applies {@code edits} onto {@code original}. A field not edited keeps its value from {@code currentValues}, else
     * its original text.
     *
     * @throws FieldRefusal naming {@code fields}, {@code fields[i].pointer} or {@code fields[i].text}
     */
    static Applied apply(String type, String original, Map<String, String> currentValues, List<FieldEdit> edits) {
        if (edits == null || edits.isEmpty() || edits.size() > StepEditRules.MAX_FIELDS) {
            throw new FieldRefusal("fields", Reason.INVALID);
        }
        Set<String> sent = new HashSet<>();
        for (FieldEdit edit : edits) {
            if (!sent.add(edit.pointer())) {
                throw new FieldRefusal("fields", Reason.INVALID);
            }
        }
        List<Located> located = locate(type, original);
        Map<String, Located> byPointer = new LinkedHashMap<>();
        Map<String, String> values = new LinkedHashMap<>();
        for (Located field : located) {
            byPointer.put(field.field().pointer(), field);
            values.put(field.field().pointer(), field.field().text());
        }
        currentValues.forEach((pointer, text) -> values.computeIfPresent(pointer, (key, old) -> text));
        int total = 0;
        for (int i = 0; i < edits.size(); i++) {
            Located target = byPointer.get(edits.get(i).pointer());
            if (target == null) {
                throw new FieldRefusal("fields[" + i + "].pointer", Reason.NOT_EDITABLE);
            }
            String text = checked("fields[" + i + "].text", target.rule(), edits.get(i).text());
            total += text.length();
            values.put(target.field().pointer(), text);
        }
        if (total > StepEditRules.MAX_EDIT_CHARACTERS) {
            throw new FieldRefusal("fields", Reason.TOO_LONG);
        }
        String output = rebuild(original, located, values);
        assertStructureKept(type, original, output);
        return new Applied(output, values);
    }

    /**
     * Rebuilds the output of a stored version: {@code stored} was valid when it was stored, or is the original's own
     * text, so the request limits and the per-field rules of {@link #apply} do not run again. Its pointers must all be
     * editable fields of {@code original} and its values must be texts; a field it lacks keeps its original text.
     *
     * @throws FieldRefusal when a pointer is not an editable field of {@code original} or a value is not a text
     */
    static Applied restore(String type, String original, Map<String, String> stored) {
        List<Located> located = locate(type, original);
        Map<String, String> values = new LinkedHashMap<>();
        for (Located field : located) {
            values.put(field.field().pointer(), field.field().text());
        }
        for (Map.Entry<String, String> entry : stored.entrySet()) {
            if (!values.containsKey(entry.getKey())) {
                throw new FieldRefusal("fields", Reason.NOT_EDITABLE);
            }
            if (entry.getValue() == null) {
                throw new FieldRefusal("fields", Reason.INVALID);
            }
            values.put(entry.getKey(), entry.getValue());
        }
        String output = rebuild(original, located, values);
        assertStructureKept(type, original, output);
        return new Applied(output, values);
    }

    /**
     * Fails when {@code edited} differs from {@code original} anywhere but at an editable field, so a defect in
     * {@link #apply} can never store a structural change.
     *
     * @throws IllegalStateException on a difference
     */
    static void assertStructureKept(String type, String original, String edited) {
        List<Located> located = locate(type, original);
        if (located.isEmpty() || isWholeText(located.getFirst().rule())) {
            return;
        }
        JsonNode before = parse(original);
        JsonNode after = parse(edited);
        if (before == null || after == null) {
            throw new IllegalStateException("An edited output is not JSON");
        }
        for (Located field : located) {
            if (!blank(before, field.field().pointer()) || !blank(after, field.field().pointer())) {
                throw new IllegalStateException("An edited output lost an editable field");
            }
        }
        if (!before.equals(after)) {
            throw new IllegalStateException("An edit changed more than the editable fields");
        }
    }

    /** Whether {@code text}, trimmed, is a JSON object or array: structure a later step parses, not plain text. */
    static boolean isJsonContainer(String text) {
        String trimmed = text.trim();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return false;
        }
        JsonNode node = parse(trimmed);
        return node != null && (node.isObject() || node.isArray());
    }

    private static String checked(String field, FieldRule rule, String requested) {
        if (requested == null) {
            throw new FieldRefusal(field, Reason.INVALID);
        }
        String text = requested.replace("\u0000", "");
        if (!rule.allowBlank() && text.isBlank()) {
            throw new FieldRefusal(field, Reason.BLANK);
        }
        if (text.length() > rule.maxCharacters()) {
            throw new FieldRefusal(field, Reason.TOO_LONG);
        }
        if (isWholeText(rule) && isJsonContainer(text)) {
            throw new FieldRefusal(field, Reason.STRUCTURE);
        }
        return text;
    }

    private static boolean isWholeText(FieldRule rule) {
        return rule.pointerTemplate().isEmpty();
    }

    private static List<Located> locate(String type, String output) {
        Optional<StepEditRules.FieldRow> row = StepEditRules.fieldsOf(type);
        if (row.isEmpty() || output == null) {
            return List.of();
        }
        List<Located> found = new ArrayList<>();
        JsonNode tree = null;
        for (FieldRule rule : row.get().fields()) {
            if (isWholeText(rule)) {
                if (!isJsonContainer(output)) {
                    found.add(new Located(new Field("", rule.label(), null, null, output), rule));
                }
                continue;
            }
            tree = tree == null ? parse(output) : tree;
            if (tree != null) {
                collect(tree, rule.pointerTemplate().substring(1).split("/"), 0, "", null, null, rule, found);
            }
        }
        return found;
    }

    private static void collect(JsonNode node, String[] parts, int at, String pointer, JsonNode element,
            Integer position, FieldRule rule, List<Located> found) {
        if (at == parts.length) {
            if (node.isTextual()) {
                found.add(new Located(new Field(pointer, rule.label(), indexOf(element, position),
                        sourceOf(element), node.textValue()), rule));
            }
            return;
        }
        if ("*".equals(parts[at])) {
            if (!node.isArray()) {
                return;
            }
            for (int i = 0; i < node.size(); i++) {
                collect(node.get(i), parts, at + 1, pointer + "/" + i, node.get(i), i, rule, found);
            }
            return;
        }
        JsonNode child = node.isObject() ? node.get(parts[at]) : null;
        if (child != null) {
            collect(child, parts, at + 1, pointer + "/" + parts[at], element, position, rule, found);
        }
    }

    private static Integer indexOf(JsonNode element, Integer position) {
        if (element == null) {
            return null;
        }
        JsonNode index = element.path(INDEX_KEY);
        return index.isIntegralNumber() && index.canConvertToInt() ? index.intValue() : position;
    }

    private static String sourceOf(JsonNode element) {
        return element != null && element.path(SOURCE_KEY).isTextual() ? element.get(SOURCE_KEY).textValue() : null;
    }

    private static String rebuild(String original, List<Located> located, Map<String, String> values) {
        if (located.isEmpty()) {
            throw new IllegalStateException("A step without editable fields cannot be edited");
        }
        if (isWholeText(located.getFirst().rule())) {
            return values.get("");
        }
        JsonNode tree = parse(original);
        for (Located field : located) {
            String text = values.get(field.field().pointer());
            if (!text.equals(field.field().text())) {
                JsonPointer pointer = JsonPointer.compile(field.field().pointer());
                ((ObjectNode) tree.at(pointer.head())).put(pointer.last().getMatchingProperty(), text);
            }
        }
        try {
            return JSON.writeValueAsString(tree);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("An edited output cannot be written", e);
        }
    }

    /** Replaces the text at {@code pointer} with the empty string; false when it is not a string there. */
    private static boolean blank(JsonNode tree, String pointer) {
        JsonPointer compiled = JsonPointer.compile(pointer);
        JsonNode parent = tree.at(compiled.head());
        if (!(parent instanceof ObjectNode object) || !object.path(compiled.last().getMatchingProperty()).isTextual()) {
            return false;
        }
        object.put(compiled.last().getMatchingProperty(), "");
        return true;
    }

    private static JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
