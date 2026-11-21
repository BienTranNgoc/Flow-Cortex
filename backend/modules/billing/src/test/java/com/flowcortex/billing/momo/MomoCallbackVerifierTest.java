package com.flowcortex.billing.momo;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.billing.payment.PaymentProviderEvent;
import com.flowcortex.billing.provider.CallbackVerification;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class MomoCallbackVerifierTest {

    private static final String ACCESS_KEY = "test-access-key-0001";
    private static final String SECRET_KEY = "test-secret-key-0001";
    private static final String ORDER_REF = "fc_0123456789abcdef0123456789abcdef";
    private static final String REQUEST_REF = "5f0c7c1e-8f7e-4a53-9a38-2f6d6f3f9c11";
    private static final long RESPONSE_TIME = 1_762_761_600_000L;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-10T08:00:00Z"), ZoneOffset.UTC);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static Schema schema;

    @BeforeAll
    static void loadSchema() throws IOException {
        String dir = Objects.requireNonNull(System.getProperty("flowcortex.contracts.dir"),
                "system property flowcortex.contracts.dir is not set (see modules/billing/build.gradle.kts)");
        try (InputStream in = Files.newInputStream(Path.of(dir, "payment-provider-event.schema.json"))) {
            schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(MAPPER.readTree(in));
        }
    }

    private static ObjectNode callback() {
        return MAPPER.createObjectNode()
                .put("partnerCode", "MOMOTEST")
                .put("orderId", ORDER_REF)
                .put("requestId", REQUEST_REF)
                .put("amount", 50000)
                .put("orderInfo", "Nodi credits")
                .put("orderType", "momo_wallet")
                .put("transId", 4_000_000_000_001L)
                .put("resultCode", 0)
                .put("message", "Successful.")
                .put("payType", "qr")
                .put("responseTime", RESPONSE_TIME)
                .put("extraData", "");
    }

    private static byte[] signed(ObjectNode body) {
        ObjectNode view = body.deepCopy().put("accessKey", ACCESS_KEY);
        String signature = MomoSigning.hmacHex(
                MomoSigning.signingString("ipn.oneTime", MomoSignedFields.IPN_ONE_TIME, view), SECRET_KEY);
        return body.deepCopy().put("signature", signature).toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] signedWith(Consumer<ObjectNode> change) {
        ObjectNode body = callback();
        change.accept(body);
        return signed(body);
    }

    private static CallbackVerification verify(byte[] body) {
        return MomoCallbackVerifier.verify(body, ACCESS_KEY, SECRET_KEY, CLOCK);
    }

    private static CallbackVerification.Rejected rejected(byte[] body) {
        return (CallbackVerification.Rejected) verify(body);
    }

    private static byte[] raw(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void aValidIpnBecomesAnInboundPaymentCallbackEventThatMatchesTheSchema() {
        CallbackVerification result = verify(signed(callback()));

        assertThat(result).isInstanceOf(CallbackVerification.Verified.class);
        var verified = (CallbackVerification.Verified) result;
        PaymentProviderEvent event = verified.event();
        assertThat(event.kind()).isEqualTo(PaymentProviderEvent.Kind.INBOUND_PAYMENT_CALLBACK);
        assertThat(event.provider()).isEqualTo("momo");
        assertThat(event.resultCode()).isEqualTo("0");
        assertThat(event.resultMessage()).isEqualTo("Successful.");
        assertThat(event.amount()).isEqualTo(50000L);
        assertThat(event.providerPayType()).isEqualTo("qr");
        assertThat(event.providerTimestamp()).isEqualTo(Instant.ofEpochMilli(RESPONSE_TIME));
        assertThat(event.receivedAt()).isEqualTo(CLOCK.instant());
        assertThat(event.reference().operationRef()).isEqualTo(ORDER_REF);
        assertThat(event.reference().requestRef()).isEqualTo(REQUEST_REF);
        assertThat(event.reference().providerTransactionId()).isEqualTo("4000000000001");
        assertThat(verified.requestId()).isEqualTo(REQUEST_REF);
        assertThat(verified.payload().get("orderId").asString()).isEqualTo(ORDER_REF);
        assertThat(schema.validate(MAPPER.valueToTree(event))).isEmpty();
    }

    private static byte[] signedThenChanged(Consumer<ObjectNode> change) {
        ObjectNode body = (ObjectNode) MAPPER.readTree(signed(callback()));
        change.accept(body);
        return body.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void aCallbackWithoutAmountIsUnrecognised() {
        assertThat(rejected(signedThenChanged(b -> b.remove("amount"))).reason())
                .isEqualTo("unrecognised_callback");
    }

    @Test
    void aMissingPayTypeIsUnrecognised() {
        assertThat(rejected(signedThenChanged(b -> b.remove("payType"))).reason())
                .isEqualTo("unrecognised_callback");
    }

    @Test
    void aFractionalAmountIsUnrecognised() {
        assertThat(rejected(signedThenChanged(b -> b.put("amount", 50000.5))).reason())
                .isEqualTo("unrecognised_callback");
    }

    @Test
    void aZeroTransactionIdIsNoProviderTransactionId() {
        var verified = (CallbackVerification.Verified) verify(signedWith(b -> b.put("transId", 0)));

        assertThat(verified.event().reference().providerTransactionId()).isNull();
    }

    @Test
    void anUnsignedFieldAddedAfterSigningStillVerifies() {
        ObjectNode withExtra = MAPPER.createObjectNode();
        withExtra.setAll((ObjectNode) MAPPER.readTree(signed(callback())));
        withExtra.put("lang", "vi");

        assertThat(verify(withExtra.toString().getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(CallbackVerification.Verified.class);
    }

    @Test
    void aTamperedAmountIsASignatureMismatch() {
        ObjectNode signedBody = (ObjectNode) MAPPER.readTree(signed(callback()));
        signedBody.put("amount", 5000);

        var result = rejected(signedBody.toString().getBytes(StandardCharsets.UTF_8));

        assertThat(result.reason()).isEqualTo("signature_mismatch");
        assertThat(result.requestId()).isEqualTo(REQUEST_REF);
    }

    @Test
    void aMissingSignatureIsReportedAsSuch() {
        assertThat(rejected(raw(callback().toString())).reason()).isEqualTo("missing_signature");
    }

    @Test
    void bodiesThatAreNotASingleJsonObjectAreUnparseable() {
        assertThat(rejected(raw("[]")).reason()).isEqualTo("unparseable_body");
        assertThat(rejected(raw("\"x\"")).reason()).isEqualTo("unparseable_body");
        assertThat(rejected(raw("{\"amount\":")).reason()).isEqualTo("unparseable_body");
        assertThat(rejected(raw("{\"a\":1} {\"b\":2}")).reason()).isEqualTo("unparseable_body");
    }

    @Test
    void aDuplicatedKeyIsUnparseable() {
        String signed = new String(signed(callback()), StandardCharsets.UTF_8);
        String duplicated = signed.substring(0, signed.length() - 1) + ",\"amount\":2000}";

        assertThat(rejected(raw(duplicated)).reason()).isEqualTo("unparseable_body");
    }

    @Test
    void anEmptyBodyIsMissing() {
        assertThat(rejected(new byte[0]).reason()).isEqualTo("missing_body");
    }

    @Test
    void aBodyOverTheCeilingIsRefusedWithoutParsing() {
        byte[] body = new byte[MomoCallbackVerifier.MAX_BODY_BYTES + 1];
        java.util.Arrays.fill(body, (byte) ' ');

        assertThat(rejected(body).reason()).isEqualTo("body_too_large");
        assertThat(MomoCallbackVerifier.MAX_BODY_BYTES).isEqualTo(16384);
    }

    @Test
    void aVerifiedCallbackForAnotherSystemsReferencesIsNotOurs() {
        assertThat(rejected(signedWith(b -> b.put("orderId", "xo_9f2c"))).reason())
                .isEqualTo("reference_not_ours");
        assertThat(rejected(signedWith(b -> b.put("requestId", "1-1-1-1-1"))).reason())
                .isEqualTo("reference_not_ours");
    }

    @Test
    void aMessageBeyondTheEventBoundsIsOutOfBounds() {
        assertThat(rejected(signedWith(b -> b.put("message", "x".repeat(600)))).reason())
                .isEqualTo("event_out_of_bounds");
    }

    @Test
    void aNulInsideAValueIsOutOfBounds() {
        assertThat(rejected(signedWith(b -> b.put("message", "bad\u0000value"))).reason())
                .isEqualTo("event_out_of_bounds");
    }

    @Test
    void anOversizedRequestIdIsStoredAsItsDigest() {
        String longId = "r".repeat(200);

        var result = rejected(signedWith(b -> b.put("requestId", longId)));

        assertThat(result.requestId()).matches("^sha256:[0-9a-f]{64}$");
    }

    @Test
    void anAbsentOrUnreadableRequestIdIsReplacedByAnUnidentifiedMarker() {
        assertThat(rejected(raw("not json")).requestId()).matches("^unidentified:[0-9a-f-]{36}$");
        assertThat(rejected(raw("{\"orderId\":\"x\"}")).requestId()).matches("^unidentified:[0-9a-f-]{36}$");
        assertThat(rejected(raw("{\"requestId\":\"a\\u0000b\"}")).requestId())
                .matches("^unidentified:[0-9a-f-]{36}$");
        assertThat(rejected(raw("{\"requestId\":123}")).requestId()).matches("^unidentified:[0-9a-f-]{36}$");
    }

    @Test
    void aRequestIdOfExactlyTheLimitIsKeptVerbatim() {
        String id = "r".repeat(120);

        assertThat(rejected(raw("{\"requestId\":\"" + id + "\"}")).requestId()).isEqualTo(id);
    }
}
