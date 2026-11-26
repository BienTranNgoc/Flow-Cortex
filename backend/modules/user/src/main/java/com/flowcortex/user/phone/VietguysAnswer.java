package com.flowcortex.user.phone;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Maps one VietGuys answer to a message to exactly one {@link Kind}. The body is read before the
 * HTTP status: a JSON object decides by its {@code error_code}, any other body is unconfirmed on a
 * 2xx and unavailable otherwise, except a 401, which is a refused token.
 */
final class VietguysAnswer {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int DUPLICATE = -16;
    private static final int OUT_OF_CREDIT = -9;
    private static final Set<Integer> AUTH_REJECTED = Set.of(-5, 12);
    private static final Set<Integer> NUMBER_REJECTED = Set.of(-10, -11);
    private static final Set<Integer> CONFIG_REJECTED =
            Set.of(11, -1, -3, -4, -6, -7, -8, -13, -14, -18, -20, -21, -22, -23);

    /** The result of one answer; a sent kind carries its delivery, every other kind its failure reason. */
    enum Kind {
        ACCEPTED(SmsDelivery.ACCEPTED, null),
        DUPLICATE(SmsDelivery.DUPLICATE, null),
        UNCONFIRMED(SmsDelivery.UNCONFIRMED, null),
        NUMBER_REJECTED(null, SmsSendFailure.Reason.NUMBER_REJECTED),
        AUTH_REJECTED(null, SmsSendFailure.Reason.UNAVAILABLE),
        CONFIG_REJECTED(null, SmsSendFailure.Reason.UNAVAILABLE),
        OUT_OF_CREDIT(null, SmsSendFailure.Reason.UNAVAILABLE),
        UNAVAILABLE(null, SmsSendFailure.Reason.UNAVAILABLE);

        private final SmsDelivery delivery;
        private final SmsSendFailure.Reason failure;

        Kind(SmsDelivery delivery, SmsSendFailure.Reason failure) {
            this.delivery = delivery;
            this.failure = failure;
        }

        boolean countsAsSent() {
            return delivery != null;
        }

        Optional<SmsDelivery> delivery() {
            return Optional.ofNullable(delivery);
        }

        Optional<SmsSendFailure.Reason> failure() {
            return Optional.ofNullable(failure);
        }
    }

    private VietguysAnswer() {
    }

    static Kind classify(int status, byte[] body) {
        JsonNode answer = parse(body);
        if (answer == null || !answer.isObject()) {
            // The JDK drops the body of a 401 answered to a streamed POST, so the carrier's own -5 never arrives.
            if (status == 401) {
                return Kind.AUTH_REJECTED;
            }
            return status >= 200 && status < 300 ? Kind.UNCONFIRMED : Kind.UNAVAILABLE;
        }
        OptionalInt error = integerOf(answer.get("error"));
        if (error.isPresent() && error.getAsInt() == 0) {
            return Kind.ACCEPTED;
        }
        OptionalInt code = integerOf(answer.get("error_code"));
        if (code.isEmpty()) {
            return Kind.UNAVAILABLE;
        }
        int value = code.getAsInt();
        if (value == DUPLICATE) {
            return Kind.DUPLICATE;
        }
        if (AUTH_REJECTED.contains(value)) {
            return Kind.AUTH_REJECTED;
        }
        if (NUMBER_REJECTED.contains(value)) {
            return Kind.NUMBER_REJECTED;
        }
        if (CONFIG_REJECTED.contains(value)) {
            return Kind.CONFIG_REJECTED;
        }
        return value == OUT_OF_CREDIT ? Kind.OUT_OF_CREDIT : Kind.UNAVAILABLE;
    }

    /** The {@code error_code} of an answer, for the log line of a refusal; empty when the body carries none. */
    static OptionalInt errorCode(byte[] body) {
        JsonNode answer = parse(body);
        return answer != null && answer.isObject() ? integerOf(answer.get("error_code")) : OptionalInt.empty();
    }

    /** An integer, or a string holding one, as an int; anything else is empty. */
    static OptionalInt integerOf(JsonNode value) {
        if (value == null) {
            return OptionalInt.empty();
        }
        if (value.isIntegralNumber()) {
            return value.canConvertToInt() ? OptionalInt.of(value.intValue()) : OptionalInt.empty();
        }
        if (value.isString()) {
            String text = value.stringValue().trim();
            if (text.matches("-?\\d{1,9}")) {
                return OptionalInt.of(Integer.parseInt(text));
            }
        }
        return OptionalInt.empty();
    }

    private static JsonNode parse(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            return JSON.readTree(body);
        } catch (JacksonException unreadable) {
            return null;
        }
    }
}
