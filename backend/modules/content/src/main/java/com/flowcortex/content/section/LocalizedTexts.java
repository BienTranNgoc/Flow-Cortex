package com.flowcortex.content.section;

import com.flowcortex.content.api.FieldError;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** Checks and reads the {@code {vi, en}} JSON shape of a localized text. */
final class LocalizedTexts {

    static final List<String> LOCALES = List.of("vi", "en");

    private LocalizedTexts() {
    }

    /** Appends the problems of the text at {@code path}; a null or missing node counts as absent. */
    static void validate(String path, JsonNode node, int maxChars, boolean required, List<FieldError> into) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            if (required) {
                into.add(new FieldError(path, FieldError.REQUIRED));
            }
            return;
        }
        if (!node.isObject()) {
            into.add(new FieldError(path, FieldError.MALFORMED));
            return;
        }
        refuseUnknownKeys(path, node, LOCALES, into);
        for (String locale : LOCALES) {
            JsonNode value = node.get(locale);
            String valuePath = path + "." + locale;
            if (value == null || value.isNull()) {
                into.add(new FieldError(valuePath, FieldError.REQUIRED));
            } else if (!value.isString()) {
                into.add(new FieldError(valuePath, FieldError.MALFORMED));
            } else {
                String text = value.stringValue();
                if (text.codePointCount(0, text.length()) > maxChars) {
                    into.add(new FieldError(valuePath, FieldError.TOO_LONG));
                }
            }
        }
    }

    /** Appends a malformed problem at {@code path.<key>} for each key of the object {@code node} not in {@code allowed}. */
    static void refuseUnknownKeys(String path, JsonNode node, List<String> allowed, List<FieldError> into) {
        for (String key : node.propertyNames()) {
            if (!allowed.contains(key)) {
                into.add(new FieldError(path + "." + key, FieldError.MALFORMED));
            }
        }
    }

    /** Appends {@code path.<locale>} for each language whose text is absent or blank. */
    static void collectBlank(String path, JsonNode node, List<String> into) {
        for (String locale : LOCALES) {
            JsonNode value = node == null ? null : node.get(locale);
            if (value == null || !value.isString() || value.stringValue().isBlank()) {
                into.add(path + "." + locale);
            }
        }
    }
}
