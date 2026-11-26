package com.flowcortex.billing.catalog;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** The marketing bullets of a tier in both languages, read leniently so one bad stored entry never breaks the list. */
public record MarketingCopy(List<String> vi, List<String> en) {

    /** Keeps the text entries of {@code vi} and {@code en}; anything else reads as an empty list. */
    static MarketingCopy read(JsonNode stored) {
        if (stored == null || !stored.isObject()) {
            return new MarketingCopy(List.of(), List.of());
        }
        return new MarketingCopy(texts(stored.get("vi")), texts(stored.get("en")));
    }

    private static List<String> texts(JsonNode list) {
        if (list == null || !list.isArray()) {
            return List.of();
        }
        List<String> texts = new ArrayList<>();
        for (JsonNode entry : list) {
            if (entry.isString()) {
                texts.add(entry.asString());
            }
        }
        return List.copyOf(texts);
    }
}
