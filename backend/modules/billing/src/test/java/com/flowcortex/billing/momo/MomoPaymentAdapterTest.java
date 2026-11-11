package com.flowcortex.billing.momo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.billing.payment.PaymentProviderEvent;
import com.flowcortex.billing.provider.InitiatePayment;
import com.flowcortex.billing.provider.PaymentInstrument;
import com.flowcortex.billing.provider.PaymentOperationRefusedException;
import com.flowcortex.billing.provider.ProviderCallOutcome;
import com.flowcortex.billing.provider.ProviderCircuitBreaker;
import com.flowcortex.billing.provider.ProviderCircuitOpenException;
import com.flowcortex.billing.provider.ProviderTransportException;
import com.flowcortex.billing.provider.QueryRefund;
import com.flowcortex.billing.provider.QueryTransaction;
import com.flowcortex.billing.provider.Refund;
import com.flowcortex.billing.provider.RefundQueryOutcome;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class MomoPaymentAdapterTest {

    private static final String ACCESS_KEY = "test-access-key-0001";
    private static final String SECRET_KEY = "test-secret-key-0001";
    private static final String ORDER = "fc_0123456789abcdef0123456789abcdef";
    private static final String REQUEST = "5f0c7c1e-8f7e-4a53-9a38-2f6d6f3f9c11";
    private static final Instant NOW = Instant.parse("2026-11-10T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static Schema schema;

    /** Records what was sent and answers from a queue: a JSON text, or the exception to throw. */
    private static final class FakeTransport implements MomoTransport {

        final List<String> paths = new ArrayList<>();
        final List<ObjectNode> bodies = new ArrayList<>();
        final Queue<Object> answers = new LinkedList<>();

        @Override
        public JsonNode post(String path, ObjectNode body) {
            paths.add(path);
            bodies.add(body);
            Object next = answers.remove();
            if (next instanceof RuntimeException failure) {
                throw failure;
            }
            return MAPPER.readTree((String) next);
        }
    }

    private FakeTransport transport;
    private SimpleMeterRegistry meters;
    private MomoPaymentAdapter adapter;

    @BeforeAll
    static void loadSchema() throws IOException {
        String dir = Objects.requireNonNull(System.getProperty("flowcortex.contracts.dir"),
                "system property flowcortex.contracts.dir is not set (see modules/billing/build.gradle.kts)");
        try (InputStream in = Files.newInputStream(Path.of(dir, "payment-provider-event.schema.json"))) {
            schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(MAPPER.readTree(in));
        }
    }

    @BeforeEach
    void setUp() {
        transport = new FakeTransport();
        meters = new SimpleMeterRegistry();
        adapter = adapter(properties(ACCESS_KEY, SECRET_KEY));
    }

    private static MomoProperties properties(String accessKey, String secretKey) {
        return new MomoProperties(true, "https://test-payment.momo.vn", "MOMOTEST", accessKey, secretKey,
                "https://app.example.com/billing/return", "https://app.example.com" + MomoCallbackRoute.PATH,
                List.of("wallet", "domestic_card"), "118.69.210.244/32", null);
    }

    private MomoPaymentAdapter adapter(MomoProperties properties) {
        MomoTelemetry telemetry = new MomoTelemetry(meters);
        ProviderCircuitBreaker breaker = new ProviderCircuitBreaker(
                new ProviderCircuitBreaker.CircuitPolicy(true, 5, Duration.ofSeconds(30)), CLOCK, telemetry);
        return new MomoPaymentAdapter(properties, transport, breaker, telemetry, CLOCK);
    }

    private static InitiatePayment opening(long amount) {
        return new InitiatePayment(ORDER, REQUEST, amount, "Nodi credits", Optional.empty());
    }

    private static ProviderTransportException timeout() {
        return new ProviderTransportException(ProviderTransportException.Kind.TIMEOUT, "momo", "/x", null);
    }

    private static String expectedSignature(String signingString) {
        return MomoSigning.hmacHex(signingString, SECRET_KEY);
    }

    private void assertNothingSent() {
        assertThat(transport.bodies).isEmpty();
    }

    @Test
    void openingSendsTheDeclaredFieldsSignedAndReturnsThePayUrl() {
        transport.answers.add("""
                {"resultCode":0,"message":"Successful.","payUrl":"https://pay.example/1","responseTime":1762761600000}""");

        ProviderCallOutcome outcome = adapter.initiatePayment(opening(50_000));

        ObjectNode sent = transport.bodies.get(0);
        assertThat(transport.paths).containsExactly("/v2/gateway/api/create");
        assertThat(sent.get("requestType").asString()).isEqualTo("captureWallet");
        assertThat(sent.get("amount").longValue()).isEqualTo(50_000L);
        assertThat(sent.get("lang").asString()).isEqualTo("vi");
        assertThat(sent.get("extraData").asString()).isEmpty();
        assertThat(sent.has("accessKey")).isFalse();
        String expected = "accessKey=" + ACCESS_KEY + "&amount=50000&extraData=&ipnUrl=https://app.example.com"
                + MomoCallbackRoute.PATH + "&orderId=" + ORDER + "&orderInfo=Nodi credits&partnerCode=MOMOTEST"
                + "&redirectUrl=https://app.example.com/billing/return&requestId=" + REQUEST
                + "&requestType=captureWallet";
        assertThat(sent.get("signature").asString()).isEqualTo(expectedSignature(expected));
        PaymentProviderEvent event = outcome.event();
        assertThat(event.kind()).isEqualTo(PaymentProviderEvent.Kind.PAYMENT_INITIATION);
        assertThat(event.reference().operationRef()).isEqualTo(ORDER);
        assertThat(event.reference().requestRef()).isEqualTo(REQUEST);
        assertThat(event.resultCode()).isEqualTo("0");
        assertThat(event.receivedAt()).isEqualTo(NOW);
        assertThat(outcome.payload().get("payUrl").asString()).isEqualTo("https://pay.example/1");
        assertThat(schema.validate(MAPPER.valueToTree(event))).isEmpty();
    }

    @Test
    void aCardInstrumentOpensThePayWithAtmPage() {
        transport.answers.add("{\"resultCode\":0}");

        adapter.initiatePayment(new InitiatePayment(ORDER, REQUEST, 50_000, "x",
                Optional.of(PaymentInstrument.DOMESTIC_CARD)));

        assertThat(transport.bodies.get(0).get("requestType").asString()).isEqualTo("payWithATM");
    }

    @Test
    void openingsOutsideTheRangeOrWithBadInputsAreRefusedAndNothingIsSent() {
        assertRefused(opening(999), PaymentOperationRefusedException.Reason.AMOUNT_OUTSIDE_PROVIDER_RANGE);
        assertRefused(opening(50_000_001), PaymentOperationRefusedException.Reason.AMOUNT_OUTSIDE_PROVIDER_RANGE);
        assertRefused(new InitiatePayment("xo_123", REQUEST, 50_000, "x", Optional.empty()),
                PaymentOperationRefusedException.Reason.INVALID_REFERENCE);
        assertRefused(new InitiatePayment(ORDER, "1-1-1-1-1", 50_000, "x", Optional.empty()),
                PaymentOperationRefusedException.Reason.INVALID_REFERENCE);
        assertRefused(new InitiatePayment(ORDER, REQUEST, 50_000, "  ", Optional.empty()),
                PaymentOperationRefusedException.Reason.INVALID_DESCRIPTION);
        assertNothingSent();
    }

    @Test
    void theRangeEdgesAreSent() {
        transport.answers.add("{\"resultCode\":0}");
        transport.answers.add("{\"resultCode\":0}");

        adapter.initiatePayment(opening(1_000));
        adapter.initiatePayment(opening(50_000_000));

        assertThat(transport.bodies).hasSize(2);
    }

    @Test
    void anInstrumentThatIsNotOfferedIsRefused() {
        MomoProperties walletOnly = new MomoProperties(true, null, "MOMOTEST", ACCESS_KEY, SECRET_KEY, "https://r",
                "https://x" + MomoCallbackRoute.PATH, List.of("wallet"), "1.2.3.4", null);

        assertThatThrownBy(() -> adapter(walletOnly).initiatePayment(new InitiatePayment(ORDER, REQUEST, 50_000, "x",
                Optional.of(PaymentInstrument.DOMESTIC_CARD))))
                .isInstanceOfSatisfying(PaymentOperationRefusedException.class,
                        e -> assertThat(e.reason())
                                .isEqualTo(PaymentOperationRefusedException.Reason.INSTRUMENT_NOT_OFFERED));
        assertNothingSent();
    }

    private void assertRefused(InitiatePayment operation, PaymentOperationRefusedException.Reason reason) {
        assertThatThrownBy(() -> adapter.initiatePayment(operation))
                .isInstanceOfSatisfying(PaymentOperationRefusedException.class,
                        e -> assertThat(e.reason()).isEqualTo(reason));
    }

    @Test
    void aZeroTransactionIdAndAnEmptyPayTypeAreAbsent() {
        transport.answers.add("{\"resultCode\":0,\"transId\":0,\"payType\":\"\",\"amount\":50000}");

        PaymentProviderEvent event = adapter.queryTransaction(new QueryTransaction(ORDER, REQUEST)).event();

        assertThat(event.reference().providerTransactionId()).isNull();
        assertThat(event.providerPayType()).isNull();
        assertThat(event.amount()).isEqualTo(50_000L);
    }

    @Test
    void anErrorCodeOnlyAnswerBecomesResultCode1001() {
        transport.answers.add("{\"errorCode\":1001,\"message\":\"x\"}");

        PaymentProviderEvent event = adapter.queryTransaction(new QueryTransaction(ORDER, REQUEST)).event();

        assertThat(event.resultCode()).isEqualTo("1001");
        assertThat(schema.validate(MAPPER.valueToTree(event))).isEmpty();
    }

    @Test
    void queryAndRefundSendTheirDeclaredFieldsAndNameTheirKinds() {
        transport.answers.add("{\"resultCode\":0,\"transId\":77}");
        transport.answers.add("{\"resultCode\":0,\"transId\":88}");

        PaymentProviderEvent query = adapter.queryTransaction(new QueryTransaction(ORDER, REQUEST)).event();
        PaymentProviderEvent refund =
                adapter.refund(new Refund(ORDER, REQUEST, "4000000000001", 20_000, "refund")).event();

        assertThat(query.kind()).isEqualTo(PaymentProviderEvent.Kind.TRANSACTION_QUERY);
        assertThat(refund.kind()).isEqualTo(PaymentProviderEvent.Kind.REFUND);
        assertThat(transport.paths).containsExactly("/v2/gateway/api/query", "/v2/gateway/api/refund");
        assertThat(transport.bodies.get(0).get("signature").asString()).isEqualTo(expectedSignature(
                "accessKey=" + ACCESS_KEY + "&orderId=" + ORDER + "&partnerCode=MOMOTEST&requestId=" + REQUEST));
        ObjectNode refundBody = transport.bodies.get(1);
        assertThat(refundBody.get("transId").isIntegralNumber()).isTrue();
        assertThat(refundBody.get("signature").asString()).isEqualTo(expectedSignature(
                "accessKey=" + ACCESS_KEY + "&amount=20000&description=refund&orderId=" + ORDER
                        + "&partnerCode=MOMOTEST&requestId=" + REQUEST + "&transId=4000000000001"));
    }

    @Test
    void aRefundWithoutAProviderTransactionIdIsRefusedBeforeSending() {
        for (String missing : new String[] {null, "", "abc"}) {
            assertThatThrownBy(() -> adapter.refund(new Refund(ORDER, REQUEST, missing, 20_000, "refund")))
                    .isInstanceOfSatisfying(PaymentOperationRefusedException.class,
                            e -> {
                                assertThat(e.reason())
                                        .isEqualTo(PaymentOperationRefusedException.Reason.INVALID_REFERENCE);
                                assertThat(e.getMessage()).contains("refund.transId");
                            });
        }
        assertNothingSent();
    }

    @Test
    void aRefundQuerySendsItsFieldsAndReturnsTheVerdict() {
        transport.answers.add("""
                {"resultCode":0,"refundTrans":[{"orderId":"%s","transId":123,"amount":20000,"resultCode":0}]}"""
                .formatted(ORDER));

        RefundQueryOutcome outcome = adapter.queryRefund(new QueryRefund(ORDER, REQUEST));

        assertThat(outcome).isEqualTo(new RefundQueryOutcome.Settled("123", 20_000, "0"));
        assertThat(transport.paths).containsExactly("/v2/gateway/api/refund/query");
    }

    @Test
    void aTimeoutIsRethrownUnresolvedAndABrokenRefundQueryIsUnknown() {
        transport.answers.add(timeout());
        transport.answers.add(timeout());

        assertThatThrownBy(() -> adapter.queryTransaction(new QueryTransaction(ORDER, REQUEST)))
                .isInstanceOf(ProviderTransportException.class);
        assertThat(adapter.queryRefund(new QueryRefund(ORDER, REQUEST))).isEqualTo(new RefundQueryOutcome.Unknown(null));
    }

    @Test
    void anOpenCircuitIsRaisedEvenForARefundQuery() {
        for (int i = 0; i < 5; i++) {
            transport.answers.add(timeout());
            assertThatThrownBy(() -> adapter.queryTransaction(new QueryTransaction(ORDER, REQUEST)))
                    .isInstanceOf(ProviderTransportException.class);
        }

        assertThatThrownBy(() -> adapter.queryRefund(new QueryRefund(ORDER, REQUEST)))
                .isInstanceOf(ProviderCircuitOpenException.class);
        assertThat(transport.bodies).hasSize(5);
        assertThat(meters.get("payment.provider.call.failure").tag("kind", "timeout").counter().count()).isEqualTo(5.0);
        assertThat(meters.get("payment.provider.circuit.transition").tag("from", "closed").tag("to", "open")
                .counter().count()).isEqualTo(1.0);
        assertThat(meters.get("payment.provider.circuit.refused").counter().count()).isEqualTo(1.0);
    }

    @Test
    void anAnswerIsCountedByResultCode() {
        transport.answers.add("{\"resultCode\":1006}");

        adapter.queryTransaction(new QueryTransaction(ORDER, REQUEST));

        assertThat(meters.get("payment.provider.call").tag("result_code", "1006").tag("endpoint", "query").counter()
                .count()).isEqualTo(1.0);
        assertThat(meters.get("payment.provider.call.duration").timer().count()).isEqualTo(1L);
    }

    @Test
    void aRejectedSignatureIsCountedAndOtherCodesAreNot() {
        transport.answers.add("""
                {"resultCode":13,"message":"x","subErrors":[{"field":"signature","message":"accessKey=%s&orderId=OTHER"}]}"""
                .formatted(ACCESS_KEY));
        transport.answers.add("{\"resultCode\":1006}");

        adapter.queryTransaction(new QueryTransaction(ORDER, REQUEST));
        adapter.queryTransaction(new QueryTransaction(ORDER, REQUEST));

        assertThat(meters.get("payment.provider.signature.failure").tag("side", "outbound_rejected").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void aMissingCredentialNamesTheProperty() {
        MomoPaymentAdapter noSecret = adapter(properties(ACCESS_KEY, ""));

        assertThatThrownBy(() -> noSecret.queryTransaction(new QueryTransaction(ORDER, REQUEST)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("flowcortex.billing.momo.secret-key")
                .hasMessageNotContaining(ACCESS_KEY);
        assertNothingSent();
    }

    @Test
    void anAnswerBeyondTheEventBoundsIsUnresolved() {
        transport.answers.add("{\"resultCode\":\"" + "9".repeat(40) + "\"}");

        assertThatThrownBy(() -> adapter.queryTransaction(new QueryTransaction(ORDER, REQUEST)))
                .isInstanceOfSatisfying(ProviderTransportException.class,
                        e -> assertThat(e.kind()).isEqualTo(ProviderTransportException.Kind.MALFORMED_RESPONSE));
        assertThat(meters.get("payment.provider.call.failure").tag("kind", "malformed_response").counter().count())
                .isEqualTo(1.0);
    }
}
