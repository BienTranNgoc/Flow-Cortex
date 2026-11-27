package com.flowcortex.user.audit;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The validated filters of an audit list or export. {@code pattern} is the search text lower-cased and
 * escaped for a {@code LIKE ... ESCAPE '\'} over the lower-cased search expression of the trigram index.
 */
record AuditLogFilter(String q, String pattern, String action, String resourceType, String resourceId,
        UUID actorUserId, String status, Instant from, Instant to) {

    static final int Q_MIN = 3;
    static final int Q_MAX = 100;
    private static final Set<String> STATUSES = Set.of("success", "failed");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** A request that breaks a filter rule; {@code errors} maps each parameter to a message bundle key. */
    static final class Invalid extends RuntimeException {

        private final transient Map<String, String> errors;

        Invalid(Map<String, String> errors) {
            super("invalid audit log parameters: " + errors.keySet());
            this.errors = Map.copyOf(errors);
        }

        Map<String, String> errors() {
            return errors;
        }
    }

    static AuditLogFilter of(String q, String action, String resourceType, String resourceId, UUID actorUserId,
            String status, Instant from, Instant to) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (q != null) {
            int length = q.codePointCount(0, q.length());
            if (length < Q_MIN || length > Q_MAX) {
                errors.put("q", "audit.filter.q");
            }
        }
        if (status != null && !STATUSES.contains(status)) {
            errors.put("status", "audit.filter.status");
        }
        if (from != null && to != null && !from.isBefore(to)) {
            errors.put("from", "audit.filter.range");
        }
        if (!errors.isEmpty()) {
            throw new Invalid(errors);
        }
        return new AuditLogFilter(q, q == null ? null : likePattern(q), action, resourceType, resourceId,
                actorUserId, status, from, to);
    }

    /** The filters the caller gave, plus the matched row count: the change summary of an export's audit row. */
    ObjectNode givenAsJson(long rowCount) {
        ObjectNode given = JSON.createObjectNode();
        putIfPresent(given, "q", q);
        putIfPresent(given, "action", action);
        putIfPresent(given, "resourceType", resourceType);
        putIfPresent(given, "resourceId", resourceId);
        putIfPresent(given, "actorUserId", actorUserId == null ? null : actorUserId.toString());
        putIfPresent(given, "status", status);
        putIfPresent(given, "from", from == null ? null : from.toString());
        putIfPresent(given, "to", to == null ? null : to.toString());
        given.put("rowCount", rowCount);
        return given;
    }

    private static void putIfPresent(ObjectNode node, String name, String value) {
        if (value != null) {
            node.put(name, value);
        }
    }

    private static String likePattern(String q) {
        String escaped = q.toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
