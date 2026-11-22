package com.flowcortex.billing.momo;

import com.flowcortex.billing.payment.PaymentOperationRefs;
import com.flowcortex.billing.payment.PaymentProviderEvent;
import com.flowcortex.billing.provider.CallbackVerification;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Checks a one-time MoMo payment callback over the bytes as received: size, strict single-object parse,
 * signature over the declared fields, then our reference shapes and the provider-event bounds. A pure
 * function of its inputs; it counts and logs nothing, and a rejection carries a short reason code only.
 */
public final class MomoCallbackVerifier {

    /** About sixteen times a MoMo one-time callback. */
    public static final int MAX_BODY_BYTES = 16384;

    /** Longest request id stored as received; a longer one is stored as its digest. */
    static final int MAX_REQUEST_ID_CHARS = 120;

    private static final String PROVIDER = "momo";
    private static final long MAX_RESPONSE_TIME_MILLIS = 253_402_300_799_999L;
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
    private static final Pattern DIGITS = Pattern.compile("^[0-9]{1,19}$");
    private static final Pattern UUID_SHAPE = Pattern.compile(PaymentOperationRefs.UUID_PATTERN);

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();
    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private MomoCallbackVerifier() {
    }

    public static CallbackVerification verify(byte[] body, String accessKey, String secretKey, Clock clock) {
        if (body == null || body.length == 0) {
            return rejected("missing_body", null);
        }
        if (body.length > MAX_BODY_BYTES) {
            return rejected("body_too_large", null);
        }
        ObjectNode payload = parse(body);
        if (payload == null) {
            return rejected("unparseable_body", null);
        }
        String requestId = requestId(payload);

        JsonNode signature = payload.get("signature");
        if (signature == null || !signature.isString() || signature.asString().isEmpty()) {
            return new CallbackVerification.Rejected("missing_signature", requestId);
        }
        ObjectNode signingView = payload.deepCopy();
        signingView.put("accessKey", accessKey);
        String expected;
        try {
            expected = MomoSigning.hmacHex(
                    MomoSigning.signingString("ipn.oneTime", MomoSignedFields.IPN_ONE_TIME, signingView),
                    secretKey);
        } catch (MomoSigningException e) {
            return new CallbackVerification.Rejected("unrecognised_callback", requestId);
        }
        if (!MomoSigning.matches(expected, signature.asString())) {
            return new CallbackVerification.Rejected("signature_mismatch", requestId);
        }

        String orderId = payload.get("orderId").asString();
        String requestRef = payload.get("requestId").asString();
        if (!PaymentOperationRefs.isOrderRef(orderId) || !UUID_SHAPE.matcher(requestRef).matches()) {
            return new CallbackVerification.Rejected("reference_not_ours", requestId);
        }
        PaymentProviderEvent event = containsNul(payload) ? null : event(payload, orderId, requestRef, clock);
        if (event == null || !VALIDATOR.validate(event).isEmpty()) {
            return new CallbackVerification.Rejected("event_out_of_bounds", requestId);
        }
        return new CallbackVerification.Verified(event, payload, requestId);
    }

    private static CallbackVerification.Rejected rejected(String reason, String requestId) {
        return new CallbackVerification.Rejected(reason, requestId != null ? requestId : unidentified());
    }

    private static ObjectNode parse(byte[] body) {
        try {
            JsonNode node = MAPPER.readTree(body);
            return node instanceof ObjectNode object ? object : null;
        } catch (JacksonException e) {
            return null;
        }
    }

    /** The provider's request id when readable, bounded for storage; otherwise a random unidentified marker. */
    private static String requestId(ObjectNode payload) {
        JsonNode node = payload.get("requestId");
        if (node == null || !node.isString()) {
            return unidentified();
        }
        String value = node.asString();
        if (value.isBlank() || value.indexOf('\u0000') >= 0) {
            return unidentified();
        }
        return value.length() > MAX_REQUEST_ID_CHARS ? "sha256:" + sha256Hex(value) : value;
    }

    private static String unidentified() {
        return "unidentified:" + UUID.randomUUID();
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** PostgreSQL text and jsonb refuse U+0000, so such a callback is never normalised into an event. */
    private static boolean containsNul(JsonNode node) {
        if (node.isString()) {
            return node.asString().indexOf('\u0000') >= 0;
        }
        if (node.isObject()) {
            return node.properties().stream()
                    .anyMatch(entry -> entry.getKey().indexOf('\u0000') >= 0 || containsNul(entry.getValue()));
        }
        if (node.isArray()) {
            for (JsonNode element : node) {
                if (containsNul(element)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Null when a value cannot be read into the event at all; bounds are then checked by Bean Validation. */
    private static PaymentProviderEvent event(ObjectNode payload, String orderRef, String requestRef, Clock clock) {
        Long amount = wholeNumber(payload.get("amount"));
        Long responseTime = wholeNumber(payload.get("responseTime"));
        if (amount == null || responseTime == null || responseTime < 0
                || responseTime > MAX_RESPONSE_TIME_MILLIS) {
            return null;
        }
        String transId = text(payload.get("transId"));
        String providerTransactionId = transId == null || "0".equals(transId) ? null : transId;
        return new PaymentProviderEvent("1", PROVIDER, PaymentProviderEvent.Kind.INBOUND_PAYMENT_CALLBACK,
                new PaymentProviderEvent.Reference(orderRef, requestRef, providerTransactionId),
                text(payload.get("resultCode")), blankToNull(text(payload.get("message"))), amount,
                Instant.ofEpochMilli(responseTime), blankToNull(text(payload.get("payType"))), clock.instant());
    }

    private static String text(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isString()) {
            return node.asString();
        }
        return node.isIntegralNumber() ? node.bigIntegerValue().toString() : null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** An integral JSON number, or digits in a string, that fits a long; null otherwise. */
    private static Long wholeNumber(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isIntegralNumber()) {
            BigInteger value = node.bigIntegerValue();
            return value.abs().compareTo(LONG_MAX) <= 0 ? value.longValueExact() : null;
        }
        if (node.isString() && DIGITS.matcher(node.asString()).matches()) {
            BigInteger value = new BigInteger(node.asString());
            return value.compareTo(LONG_MAX) <= 0 ? value.longValueExact() : null;
        }
        return null;
    }
}
