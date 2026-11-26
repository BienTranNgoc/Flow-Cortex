package com.flowcortex.content.legal;

import com.flowcortex.content.api.FieldError;
import com.flowcortex.content.api.SectionType;
import com.flowcortex.content.section.LocalizedTexts;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Which legal documents the member app lists and in what order, each with an optional label override. */
@Component
public class LegalIndexSection implements SectionType {

    public static final String TYPE = "legalIndex";
    public static final int MAX_LABEL_CHARS = 200;

    private static final List<String> ITEM_KEYS = List.of("slug", "visible", "label");

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public int schemaVersion() {
        return 1;
    }

    @Override
    public List<String> dataFields() {
        return List.of("items");
    }

    @Override
    public List<FieldError> validate(JsonNode data) {
        List<FieldError> errors = new ArrayList<>();
        JsonNode items = data.get("items");
        if (items == null || items.isNull()) {
            errors.add(new FieldError("items", FieldError.REQUIRED));
        } else if (!items.isArray()) {
            errors.add(new FieldError("items", FieldError.MALFORMED));
        } else if (items.size() > LegalCatalogue.DOCUMENTS.size()) {
            errors.add(new FieldError("items", FieldError.TOO_MANY));
        } else {
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < items.size(); i++) {
                validateItem("items[" + i + "]", items.get(i), seen, errors);
            }
        }
        return errors;
    }

    /** An index holds no required text: an empty one is a valid publication that lists nothing. */
    @Override
    public List<String> blankRequiredTexts(JsonNode data) {
        return List.of();
    }

    private static void validateItem(String path, JsonNode item, Set<String> seen, List<FieldError> errors) {
        if (!item.isObject()) {
            errors.add(new FieldError(path, FieldError.MALFORMED));
            return;
        }
        LocalizedTexts.refuseUnknownKeys(path, item, ITEM_KEYS, errors);
        validateSlug(path + ".slug", item.get("slug"), seen, errors);
        JsonNode visible = item.get("visible");
        if (visible == null || visible.isNull()) {
            errors.add(new FieldError(path + ".visible", FieldError.REQUIRED));
        } else if (!visible.isBoolean()) {
            errors.add(new FieldError(path + ".visible", FieldError.MALFORMED));
        }
        if (item.has("label")) {
            validateLabel(path + ".label", item.get("label"), errors);
        }
    }

    private static void validateSlug(String path, JsonNode slug, Set<String> seen, List<FieldError> errors) {
        if (slug == null || slug.isNull()) {
            errors.add(new FieldError(path, FieldError.REQUIRED));
        } else if (!slug.isString()) {
            errors.add(new FieldError(path, FieldError.MALFORMED));
        } else if (!LegalCatalogue.isLegalDocument(slug.stringValue())) {
            errors.add(new FieldError(path, FieldError.UNKNOWN_DOCUMENT));
        } else if (!seen.add(slug.stringValue())) {
            errors.add(new FieldError(path, FieldError.DUPLICATE));
        }
    }

    private static void validateLabel(String path, JsonNode label, List<FieldError> errors) {
        if (!label.isObject()) {
            errors.add(new FieldError(path, FieldError.MALFORMED));
            return;
        }
        LocalizedTexts.refuseUnknownKeys(path, label, LocalizedTexts.LOCALES, errors);
        for (String locale : LocalizedTexts.LOCALES) {
            JsonNode value = label.get(locale);
            if (value == null) {
                continue;
            }
            if (!value.isString()) {
                errors.add(new FieldError(path + "." + locale, FieldError.MALFORMED));
            } else if (value.stringValue().codePointCount(0, value.stringValue().length()) > MAX_LABEL_CHARS) {
                errors.add(new FieldError(path + "." + locale, FieldError.TOO_LONG));
            }
        }
    }
}
