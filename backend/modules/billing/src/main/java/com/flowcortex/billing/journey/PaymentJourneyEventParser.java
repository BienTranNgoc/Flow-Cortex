package com.flowcortex.billing.journey;

import com.flowcortex.billing.journey.PaymentJourneyEvent.PayerReturned;
import com.flowcortex.billing.journey.PaymentJourneyEvent.PaymentOpened;
import com.flowcortex.billing.payment.PaymentOperationRefs;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the raw bytes of a journey body and returns the event it holds, or empty for anything else.
 * The body is parsed by hand rather than bound to a record because binding would ignore unknown keys,
 * coerce scalars, accept a duplicated key and read the whole body before any size check. Never throws.
 */
public final class PaymentJourneyEventParser {

    /** About eight times the largest legal body, so the read stays bounded without refusing a valid one. */
    public static final int MAX_BODY_BYTES = 2048;

    private static final String OPENED = "billing.payment_opened";
    private static final String RETURNED = "billing.payer_returned";

    private static final Set<String> OPENED_REQUIRED = Set.of("event", "orderRef", "transactionId", "replayed");
    private static final Set<String> OPENED_OPTIONAL = Set.of("planId", "packageId");
    private static final Set<String> RETURNED_REQUIRED = Set.of("event", "outcome", "orderRef");

    private static final Pattern PLAN_ID = Pattern.compile("^[A-Za-z0-9_-]{1,20}$");
    private static final Pattern UUID = Pattern.compile(PaymentOperationRefs.UUID_PATTERN);

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private PaymentJourneyEventParser() {
    }

    public static Optional<PaymentJourneyEvent> parse(byte[] body) {
        if (body == null || body.length > MAX_BODY_BYTES) {
            return Optional.empty();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (JacksonException e) {
            return Optional.empty();
        }
        if (root == null || !root.isObject()) {
            return Optional.empty();
        }
        JsonNode event = root.get("event");
        if (event == null || !event.isString()) {
            return Optional.empty();
        }
        return switch (event.asString()) {
            case OPENED -> opened(root);
            case RETURNED -> returned(root);
            default -> Optional.empty();
        };
    }

    private static Optional<PaymentJourneyEvent> opened(JsonNode root) {
        if (!keysAreDeclared(root, OPENED_REQUIRED, OPENED_OPTIONAL)) {
            return Optional.empty();
        }
        // A key that is present counts whatever its value, so a plan beside "packageId": null is both.
        boolean hasPlan = root.has("planId");
        boolean hasPackage = root.has("packageId");
        if (hasPlan == hasPackage) {
            return Optional.empty();
        }
        String orderRef = stringOf(root.get("orderRef"));
        String transactionId = stringOf(root.get("transactionId"));
        JsonNode replayed = root.get("replayed");
        String planId = hasPlan ? stringOf(root.get("planId")) : null;
        String packageId = hasPackage ? stringOf(root.get("packageId")) : null;
        boolean identifiersMatch = PaymentOperationRefs.isOrderRef(orderRef)
                && matches(UUID, transactionId)
                && (hasPlan ? matches(PLAN_ID, planId) : matches(UUID, packageId));
        if (!identifiersMatch || !replayed.isBoolean()) {
            return Optional.empty();
        }
        return Optional.of(new PaymentOpened(orderRef, transactionId, replayed.booleanValue(), planId, packageId));
    }

    private static Optional<PaymentJourneyEvent> returned(JsonNode root) {
        if (!keysAreDeclared(root, RETURNED_REQUIRED, Set.of())) {
            return Optional.empty();
        }
        Optional<PayerReturnOutcome> outcome = root.get("outcome").isString()
                ? PayerReturnOutcome.fromValue(root.get("outcome").asString())
                : Optional.empty();
        JsonNode ref = root.get("orderRef");
        if (outcome.isEmpty() || !(ref.isNull() || ref.isString())) {
            return Optional.empty();
        }
        if (ref.isNull()) {
            return Optional.of(new PayerReturned(outcome.get(), ReturnRefOrigin.ABSENT, null));
        }
        // Any string that is not ours is classified and dropped: it is caller text and never reaches a log line.
        if (PaymentOperationRefs.isOrderRef(ref.asString())) {
            return Optional.of(new PayerReturned(outcome.get(), ReturnRefOrigin.OURS, ref.asString()));
        }
        return Optional.of(new PayerReturned(outcome.get(), ReturnRefOrigin.NOT_OURS, null));
    }

    private static boolean keysAreDeclared(JsonNode root, Set<String> required, Set<String> optional) {
        for (String key : required) {
            if (!root.has(key)) {
                return false;
            }
        }
        return root.propertyNames().stream().allMatch(key -> required.contains(key) || optional.contains(key));
    }

    private static String stringOf(JsonNode node) {
        return node.isString() ? node.asString() : null;
    }

    private static boolean matches(Pattern pattern, String value) {
        return value != null && pattern.matcher(value).matches();
    }
}
