package com.flowcortex.billing.journey;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.billing.journey.PaymentJourneyEvent.PayerReturned;
import com.flowcortex.billing.journey.PaymentJourneyEvent.PaymentOpened;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PaymentJourneyEventParserTest {

    /**
     * One row per rule. {@code expected} is the event the parser must return, or null when the body must
     * be refused; {@code schemaExpressible} is false for the checks a JSON Schema cannot state.
     */
    record Fixture(String name, String body, PaymentJourneyEvent expected, boolean schemaExpressible) {

        @Override
        public String toString() {
            return name;
        }
    }

    /** Shared with the schema agreement test. */
    static final class JourneyFixtures {

        static final String ORDER_REF = "fc_0123456789abcdef0123456789abcdef";
        static final String UUID_LOWER = "5f0c7c1e-8f7e-4a53-9a38-2f6d6f3f9c11";
        static final String UUID_UPPER = "5F0C7C1E-8F7E-4A53-9A38-2F6D6F3F9C11";
        static final String PACKAGE_ID = "0b7e1d2c-3a4b-4c5d-8e6f-7a8b9c0d1e2f";

        private JourneyFixtures() {
        }

        private static String opened(String transactionId, String tail) {
            return "{\"event\":\"billing.payment_opened\",\"orderRef\":\"" + ORDER_REF + "\",\"transactionId\":\""
                    + transactionId + "\",\"replayed\":false" + tail + "}";
        }

        private static String returned(String orderRefJson) {
            return "{\"event\":\"billing.payer_returned\",\"outcome\":\"paid\",\"orderRef\":" + orderRefJson + "}";
        }

        private static String returnedPadded(int totalBytes) {
            int overhead = returned("\"\"").getBytes(StandardCharsets.UTF_8).length;
            return returned("\"" + "a".repeat(totalBytes - overhead) + "\"");
        }

        static List<Fixture> all() {
            List<Fixture> all = new ArrayList<>();
            PaymentOpened withPlan = new PaymentOpened(ORDER_REF, UUID_LOWER, false, "pro_monthly", null);
            PaymentOpened withPackage = new PaymentOpened(ORDER_REF, UUID_LOWER, false, null, PACKAGE_ID);
            PaymentOpened upperTransaction = new PaymentOpened(ORDER_REF, UUID_UPPER, false, "pro", null);
            all.add(new Fixture("opened with plan", opened(UUID_LOWER, ",\"planId\":\"pro_monthly\""), withPlan, true));
            all.add(new Fixture("opened with package",
                    opened(UUID_LOWER, ",\"packageId\":\"" + PACKAGE_ID + "\""), withPackage, true));
            all.add(new Fixture("opened with an upper-case transactionId",
                    opened(UUID_UPPER, ",\"planId\":\"pro\""), upperTransaction, true));
            all.add(new Fixture("opened with a replayed flag set",
                    opened(UUID_LOWER, ",\"planId\":\"pro\"").replace("\"replayed\":false", "\"replayed\":true"),
                    new PaymentOpened(ORDER_REF, UUID_LOWER, true, "pro", null), true));
            all.add(new Fixture("opened with both plan and package",
                    opened(UUID_LOWER, ",\"planId\":\"pro\",\"packageId\":\"" + PACKAGE_ID + "\""), null, true));
            all.add(new Fixture("opened with neither plan nor package", opened(UUID_LOWER, ""), null, true));
            all.add(new Fixture("opened with a plan id holding a space", opened(UUID_LOWER, ",\"planId\":\"a b\""),
                    null, true));
            all.add(new Fixture("opened with a 21-character plan id",
                    opened(UUID_LOWER, ",\"planId\":\"" + "a".repeat(21) + "\""), null, true));
            all.add(new Fixture("opened with a package id that is not a UUID",
                    opened(UUID_LOWER, ",\"packageId\":\"nope\""), null, true));
            all.add(new Fixture("opened with a transactionId that is not a UUID",
                    opened("nope", ",\"planId\":\"pro\""), null, true));
            all.add(new Fixture("opened with transactionId 1-1-1-1-1",
                    opened("1-1-1-1-1", ",\"planId\":\"pro\""), null, true));
            all.add(new Fixture("opened with planId beside a null packageId",
                    opened(UUID_LOWER, ",\"planId\":\"pro\",\"packageId\":null"), null, true));
            all.add(new Fixture("opened with an orderRef from another system",
                    opened(UUID_LOWER, ",\"planId\":\"pro\"")
                            .replace(ORDER_REF, "xo_0123456789abcdef0123456789abcdef"), null, true));
            all.add(new Fixture("opened with replayed as a string",
                    opened(UUID_LOWER, ",\"planId\":\"pro\"").replace("\"replayed\":false", "\"replayed\":\"true\""),
                    null, true));
            all.add(new Fixture("opened without replayed",
                    opened(UUID_LOWER, ",\"planId\":\"pro\"").replace(",\"replayed\":false", ""), null, true));
            all.add(new Fixture("opened with an undeclared userId",
                    opened(UUID_LOWER, ",\"planId\":\"pro\",\"userId\":\"42\""), null, true));

            all.add(new Fixture("returned with our reference", returned("\"" + ORDER_REF + "\""),
                    new PayerReturned(PayerReturnOutcome.PAID, ReturnRefOrigin.OURS, ORDER_REF), true));
            all.add(new Fixture("returned with a null reference", returned("null"),
                    new PayerReturned(PayerReturnOutcome.PAID, ReturnRefOrigin.ABSENT, null), true));
            all.add(new Fixture("returned with a script tag", returned("\"<script>\""),
                    new PayerReturned(PayerReturnOutcome.PAID, ReturnRefOrigin.NOT_OURS, null), true));
            all.add(new Fixture("returned with an empty reference", returned("\"\""),
                    new PayerReturned(PayerReturnOutcome.PAID, ReturnRefOrigin.NOT_OURS, null), true));
            all.add(new Fixture("returned with a 65-byte reference", returned("\"" + "a".repeat(65) + "\""),
                    new PayerReturned(PayerReturnOutcome.PAID, ReturnRefOrigin.NOT_OURS, null), true));
            all.add(new Fixture("returned at exactly 2048 bytes", returnedPadded(2048),
                    new PayerReturned(PayerReturnOutcome.PAID, ReturnRefOrigin.NOT_OURS, null), true));
            all.add(new Fixture("returned with the outcome refunded",
                    returned("null").replace("paid", "refunded"), null, true));
            all.add(new Fixture("returned with a numeric reference", returned("42"), null, true));
            all.add(new Fixture("returned with an undeclared key",
                    returned("null").replace("}", ",\"userId\":\"42\"}"), null, true));
            all.add(new Fixture("returned without a reference key",
                    "{\"event\":\"billing.payer_returned\",\"outcome\":\"paid\"}", null, true));

            all.add(new Fixture("an array", "[]", null, true));
            all.add(new Fixture("a string", "\"x\"", null, true));
            all.add(new Fixture("null", "null", null, true));
            all.add(new Fixture("an unknown event", "{\"event\":\"billing.refund_requested\"}", null, true));
            all.add(new Fixture("an event of the wrong type", "{\"event\":7}", null, true));
            all.add(new Fixture("malformed JSON", "{\"event\":", null, false));
            all.add(new Fixture("an empty body", "", null, false));
            all.add(new Fixture("a duplicated event key",
                    "{\"event\":\"billing.payer_returned\",\"event\":\"billing.payer_returned\","
                            + "\"outcome\":\"paid\",\"orderRef\":null}", null, false));
            all.add(new Fixture("a body of 2049 bytes", returnedPadded(2049), null, false));
            all.add(new Fixture("a valid body followed by a second object",
                    returned("null") + "{\"userId\":\"42\"}", null, false));
            all.add(new Fixture("a valid body followed by text", returned("null") + " x", null, false));
            return all;
        }
    }

    static Stream<Arguments> fixtures() {
        return JourneyFixtures.all().stream().map(fixture -> Arguments.of(fixture));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void everyRuleHasItsVerdict(Fixture fixture) {
        Optional<PaymentJourneyEvent> parsed = PaymentJourneyEventParser.parse(
                fixture.body().getBytes(StandardCharsets.UTF_8));

        assertThat(parsed).isEqualTo(Optional.ofNullable(fixture.expected()));
    }

    @Test
    void theByteCeilingIsTwoThousandFortyEight() {
        assertThat(PaymentJourneyEventParser.MAX_BODY_BYTES).isEqualTo(2048);
    }

    @Test
    void aNullBodyIsRefusedNotThrown() {
        assertThat(PaymentJourneyEventParser.parse(null)).isEmpty();
    }

    @Test
    void theEightOutcomeWordsAreTheWireWords() {
        assertThat(PayerReturnOutcome.values()).extracting(PayerReturnOutcome::value).containsExactlyInAnyOrder(
                "paid", "not_paid", "settled_other", "waiting", "stopped_waiting", "missing", "unreadable",
                "unidentified");
    }
}
