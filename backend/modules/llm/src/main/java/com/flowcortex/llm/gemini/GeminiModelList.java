package com.flowcortex.llm.gemini;

import com.flowcortex.llm.api.LlmJsonResult.Kind;
import com.flowcortex.llm.registry.ProbeCodes;
import com.flowcortex.llm.registry.RegisteredProvider.VendorModel;
import com.flowcortex.llm.registry.RegisteredProvider.VendorModels;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Reads Gemini's {@code models.list} answer into the text models a registry entry may name. Pure: no I/O.
 * Only the first page is read, so a {@code nextPageToken} is ignored and the list stays bounded.
 */
final class GeminiModelList {

    static final int MAX_ID_LENGTH = 128;

    private static final String PREFIX = "models/";

    private static final String GENERATE = "generateContent";

    /** A lower-case dash-separated id that starts with {@code gemini}; nothing that could alter a URL path. */
    private static final Pattern ID = Pattern.compile("gemini(-[a-z0-9.]+)+");

    /** A dash-separated segment that marks a model which cannot answer a JSON text call. */
    private static final Set<String> NON_TEXT_SEGMENTS = Set.of("tts", "image", "live", "audio", "embedding");

    private GeminiModelList() {
    }

    /** Whether the id names a Gemini text model; the rule behind both the list and a registry entry. */
    static boolean accepts(String modelKey) {
        if (modelKey == null || modelKey.length() > MAX_ID_LENGTH || !ID.matcher(modelKey).matches()) {
            return false;
        }
        for (String segment : modelKey.split("-")) {
            if (NON_TEXT_SEGMENTS.contains(segment)) {
                return false;
            }
        }
        return true;
    }

    static VendorModels parse(byte[] body) {
        JsonNode root;
        try {
            root = body == null ? null : GeminiAttempts.JSON.readTree(body);
        } catch (JacksonException ex) {
            return upstream();
        }
        if (root == null || !root.isObject()) {
            return upstream();
        }
        List<VendorModel> models = new ArrayList<>();
        for (JsonNode entry : root.path("models")) {
            String name = entry.path("name").isString() ? entry.path("name").asString() : "";
            String id = name.startsWith(PREFIX) ? name.substring(PREFIX.length()) : name;
            if (!accepts(id) || !generatesContent(entry.path("supportedGenerationMethods"))) {
                continue;
            }
            String display = entry.path("displayName").isString() ? entry.path("displayName").asString() : "";
            models.add(new VendorModel(id, display.isBlank() ? id : display));
        }
        models.sort(Comparator.comparing(VendorModel::id));
        return new VendorModels(models, null);
    }

    private static boolean generatesContent(JsonNode methods) {
        for (JsonNode method : methods) {
            if (method.isString() && GENERATE.equals(method.asString())) {
                return true;
            }
        }
        return false;
    }

    private static VendorModels upstream() {
        return new VendorModels(List.of(), ProbeCodes.code(Kind.UPSTREAM));
    }
}
