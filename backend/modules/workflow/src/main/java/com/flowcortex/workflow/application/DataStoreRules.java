package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.shared.web.LocalizedMessage;
import java.io.UncheckedIOException;
import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The write rules of a workflow's data store, as one ordered table: the first row whose predicate
 * trips decides the refusal, so one request yields one fault.
 */
final class DataStoreRules {

    static final int MAX_KEYS = 100;
    static final int MAX_VALUE_BYTES = 524_288;

    /** Same expression as the table's key CHECK. */
    private static final Pattern KEY = Pattern.compile("^(?!\\.{1,2}$)[A-Za-z0-9._-]{1,100}$");

    private static final Pattern TYPE = Pattern.compile("^[a-z0-9_-]{1,32}$");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    record Fault(String code, String field, LocalizedMessage message) {}

    private record Candidate(String key, String dataType, JsonNode value, boolean exists, long count) {}

    private record Rule(Predicate<Candidate> trips, String code, String field, Supplier<LocalizedMessage> message) {
        Fault fault() {
            return new Fault(code, field, message.get());
        }
    }

    // A \u0000 passes Jackson but PostgreSQL jsonb refuses it, which would surface as a 500.
    private static final List<Rule> RULES = List.of(
            new Rule(c -> c.key() == null || !KEY.matcher(c.key()).matches(),
                    "DATA_STORE_KEY_INVALID", "key", () -> new LocalizedMessage("workflow.dataStore.keyInvalid")),
            new Rule(c -> c.dataType() != null && !TYPE.matcher(c.dataType()).matches(),
                    "DATA_STORE_TYPE_INVALID", "dataType",
                    () -> new LocalizedMessage("workflow.dataStore.typeInvalid")),
            new Rule(c -> c.value() == null || holdsNul(c.value()),
                    "DATA_STORE_VALUE_INVALID", "value",
                    () -> new LocalizedMessage("workflow.dataStore.valueInvalid")),
            new Rule(c -> sizeOf(c.value()) > MAX_VALUE_BYTES,
                    "DATA_STORE_VALUE_TOO_LARGE", "value",
                    () -> new LocalizedMessage("workflow.dataStore.valueTooLarge", MAX_VALUE_BYTES)),
            new Rule(c -> !c.exists() && c.count() >= MAX_KEYS,
                    "DATA_STORE_QUOTA_EXCEEDED", "key",
                    () -> new LocalizedMessage("workflow.dataStore.quotaExceeded", MAX_KEYS)));

    private DataStoreRules() {}

    /** The compact UTF-8 length of the value, independent of how the client formatted it. */
    static int sizeOf(JsonNode value) {
        try {
            return MAPPER.writeValueAsBytes(value).length;
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * @param dataType {@code null} when the request names none
     * @param exists   whether the workflow already holds {@code key}
     * @param count    how many keys the workflow holds
     * @return the first fault, or {@code null} when the write is acceptable
     */
    static Fault check(String key, String dataType, JsonNode value, boolean exists, long count) {
        Candidate candidate = new Candidate(key, dataType, value, exists, count);
        return RULES.stream()
                .filter(rule -> rule.trips().test(candidate))
                .findFirst()
                .map(Rule::fault)
                .orElse(null);
    }

    private static boolean holdsNul(JsonNode node) {
        if (node.isTextual()) {
            return node.textValue().indexOf('\u0000') >= 0;
        }
        if (node.isObject()) {
            Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                if (names.next().indexOf('\u0000') >= 0) {
                    return true;
                }
            }
        }
        for (JsonNode child : node) {
            if (holdsNul(child)) {
                return true;
            }
        }
        return false;
    }
}
