package com.flowcortex.app.clienterrors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.Iterator;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Strict check of a report body: exactly the keys {@code v, kind, app, name, route} and optionally
 * {@code lang}, with their types and lengths. Pure; the result never carries caller text.
 */
@Component
class ClientErrorReportValidator {

    static final int MAX_NAME = 80;
    static final int MAX_ROUTE = 120;
    static final int MAX_LANG = 8;

    private static final Set<String> KEYS = Set.of("v", "kind", "app", "name", "route", "lang");

    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    /** Either the parsed report or the class of refusal; exactly one is non-null. */
    record Result(ClientErrorReport report, ClientErrorRefusal refusal) {

        static Result accepted(ClientErrorReport report) {
            return new Result(report, null);
        }

        static Result refused(ClientErrorRefusal refusal) {
            return new Result(null, refusal);
        }
    }

    Result validate(String body) {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (JsonProcessingException | RuntimeException e) {
            return Result.refused(ClientErrorRefusal.UNREADABLE);
        }
        if (root == null || root.isMissingNode()) {
            return Result.refused(ClientErrorRefusal.UNREADABLE);
        }
        if (!root.isObject()) {
            return Result.refused(ClientErrorRefusal.INVALID);
        }
        for (Iterator<String> keys = root.fieldNames(); keys.hasNext();) {
            if (!KEYS.contains(keys.next())) {
                return Result.refused(ClientErrorRefusal.INVALID);
            }
        }
        JsonNode version = root.get("v");
        if (version == null || !version.isInt() || version.intValue() != 1) {
            return Result.refused(ClientErrorRefusal.INVALID);
        }
        ClientErrorReport.Kind kind = ClientErrorReport.Kind.fromWire(text(root, "kind", 1, 16));
        ClientErrorReport.App app = ClientErrorReport.App.fromWire(text(root, "app", 1, 16));
        String name = text(root, "name", 1, MAX_NAME);
        String route = text(root, "route", 1, MAX_ROUTE);
        if (kind == null || app == null || name == null || route == null || !route.startsWith("/")) {
            return Result.refused(ClientErrorRefusal.INVALID);
        }
        String lang = null;
        if (root.has("lang")) {
            lang = text(root, "lang", 0, MAX_LANG);
            if (lang == null) {
                return Result.refused(ClientErrorRefusal.INVALID);
            }
        }
        return Result.accepted(new ClientErrorReport(kind, app, name, route, lang));
    }

    /** The member's text when it is a string of {@code min..max} characters, else null. */
    private static String text(JsonNode root, String key, int min, int max) {
        JsonNode node = root.get(key);
        if (node == null || !node.isTextual()) {
            return null;
        }
        String value = node.textValue();
        int length = value.codePointCount(0, value.length());
        return length >= min && length <= max ? value : null;
    }
}
