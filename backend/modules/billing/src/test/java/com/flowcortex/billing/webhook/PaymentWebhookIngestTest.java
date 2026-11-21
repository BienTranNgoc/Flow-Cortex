package com.flowcortex.billing.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.flowcortex.billing.payment.PaymentProviderEvent;
import com.flowcortex.billing.payment.PaymentProviderEvent.Kind;
import com.flowcortex.billing.provider.CallbackVerification;
import com.flowcortex.billing.provider.PaymentOrderLookup;
import com.flowcortex.billing.provider.PaymentOrderLookup.RecordedOrder;
import com.flowcortex.billing.provider.PaymentProvider;
import com.flowcortex.billing.provider.PaymentProviders;
import com.flowcortex.billing.webhook.PaymentWebhookEventRepository.InsertResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class PaymentWebhookIngestTest {

    private static final String ORDER = "fc_0123456789abcdef0123456789abcdef";
    private static final String REQUEST = "5f0c7c1e-8f7e-4a53-9a38-2f6d6f3f9c11";
    private static final Instant NOW = Instant.parse("2026-11-10T08:00:00Z");
    private static final byte[] BODY = "{\"raw\":true}".getBytes(StandardCharsets.UTF_8);
    private static final String SOURCE = "118.69.210.244";
    private static final RecordedOrder ORDER_50K = new RecordedOrder(50_000, "MOMOTEST");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final PaymentProvider provider = mock(PaymentProvider.class);
    private final PaymentOrderLookup lookup = mock(PaymentOrderLookup.class);
    private final PaymentWebhookEventRepository repository = mock(PaymentWebhookEventRepository.class);
    private final ListAppender<ILoggingEvent> logLines = new ListAppender<>();
    private ch.qos.logback.classic.Logger telemetryLogger;
    private PaymentWebhookIngest ingest;

    @BeforeEach
    void setUp() {
        when(provider.key()).thenReturn("momo");
        ingest = new PaymentWebhookIngest(new PaymentProviders(List.of(provider)), () -> lookup, repository,
                new WebhookTelemetry(meters));
        telemetryLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(WebhookTelemetry.class);
        logLines.start();
        telemetryLogger.addAppender(logLines);
    }

    @AfterEach
    void detach() {
        telemetryLogger.detachAppender(logLines);
    }

    private static PaymentProviderEvent event(Long amount) {
        return new PaymentProviderEvent("1", "momo", Kind.INBOUND_PAYMENT_CALLBACK,
                new PaymentProviderEvent.Reference(ORDER, REQUEST, "4000000000001"), "0", "Successful.", amount, NOW,
                "qr", NOW);
    }

    private static CallbackVerification.Verified verified(Long amount, String partnerCode) {
        ObjectNode payload = MAPPER.createObjectNode().put("orderId", ORDER);
        if (partnerCode != null) {
            payload.put("partnerCode", partnerCode);
        }
        return new CallbackVerification.Verified(event(amount), payload, REQUEST);
    }

    private HttpStatus handle() {
        return ingest.handle(BODY, SOURCE, NOW);
    }

    private double count(String disposition, String reason) {
        return meters.get("payment.webhook.callback").tag("disposition", disposition).tag("reason", reason)
                .counter().count();
    }

    private void assertOneTimerSample() {
        assertThat(meters.get("payment.webhook.callback.duration").timer().count()).isEqualTo(1);
    }

    private void assertRejectedRow(String reason) {
        verify(repository).insertRejected("momo", REQUEST, reason, BODY, SOURCE, NOW);
        verify(repository, never()).insertAccepted(anyString(), anyString(), any(), any(), any(), any());
    }

    private void verifiedAs(CallbackVerification verification) {
        when(provider.verifyCallback(BODY)).thenReturn(verification);
    }

    @Test
    void aFailedVerificationIsRefusedWith401AndKeepsTheBytesAndTheReason() {
        verifiedAs(new CallbackVerification.Rejected("signature_mismatch", REQUEST));

        assertThat(handle()).isEqualTo(HttpStatus.UNAUTHORIZED);

        assertRejectedRow("signature_mismatch");
        assertThat(count("rejected", "signature_mismatch")).isEqualTo(1);
        assertThat(meters.get("payment.provider.signature.failure").tag("side", "inbound_mismatch").counter().count())
                .isEqualTo(1);
        assertOneTimerSample();
    }

    @Test
    void aNonSignatureVerificationFailureIsNotCountedAsASignatureFailure() {
        verifiedAs(new CallbackVerification.Rejected("body_too_large", "unidentified:x"));

        assertThat(handle()).isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(meters.find("payment.provider.signature.failure").counter()).isNull();
        verify(repository).insertRejected("momo", "unidentified:x", "body_too_large", BODY, SOURCE, NOW);
    }

    @Test
    void aReferenceThatIsNotOursIsAnswered204AndKeptAsRawEvidence() {
        UUID row = UUID.randomUUID();
        when(repository.insertRejected(any(), any(), any(), any(), any(), any())).thenReturn(row);
        verifiedAs(new CallbackVerification.Rejected("reference_not_ours", REQUEST));

        assertThat(handle()).isEqualTo(HttpStatus.NO_CONTENT);

        assertRejectedRow("reference_not_ours");
        assertThat(count("rejected", "reference_not_ours")).isEqualTo(1);
        assertThat(logLines.list).singleElement().satisfies(line -> {
            assertThat(line.getFormattedMessage()).isEqualTo(
                    "payment_webhook_reference_not_ours provider=momo reason=reference_not_ours row=" + row);
            assertThat(line.getFormattedMessage()).doesNotContain(ORDER);
        });
        assertOneTimerSample();
    }

    @Test
    void anEventOutsideItsBoundsIsAnswered204AndLoggedAtErrorWithTheRowIdOnly() {
        UUID row = UUID.randomUUID();
        when(repository.insertRejected(any(), any(), any(), any(), any(), any())).thenReturn(row);
        verifiedAs(new CallbackVerification.Rejected("event_out_of_bounds", REQUEST));

        assertThat(handle()).isEqualTo(HttpStatus.NO_CONTENT);

        assertRejectedRow("event_out_of_bounds");
        assertThat(logLines.list).filteredOn(line -> line.getLevel() == Level.ERROR)
                .singleElement()
                .satisfies(line -> assertThat(line.getFormattedMessage())
                        .isEqualTo("payment_webhook_event_out_of_bounds provider=momo row=" + row));
        assertThat(count("rejected", "event_out_of_bounds")).isEqualTo(1);
    }

    @Test
    void aMissingOrderIsAnswered204AndRejected() {
        verifiedAs(verified(50_000L, "MOMOTEST"));
        when(lookup.find("momo", ORDER)).thenReturn(Optional.empty());

        assertThat(handle()).isEqualTo(HttpStatus.NO_CONTENT);

        assertRejectedRow("order_not_found");
        assertThat(count("rejected", "order_not_found")).isEqualTo(1);
    }

    @Test
    void aPartnerCodeOtherThanTheRecordedOneIsAMerchantMismatch() {
        verifiedAs(verified(50_000L, "OTHERSHOP"));
        when(lookup.find("momo", ORDER)).thenReturn(Optional.of(ORDER_50K));

        assertThat(handle()).isEqualTo(HttpStatus.NO_CONTENT);

        assertRejectedRow("merchant_mismatch");
        assertThat(count("rejected", "merchant_mismatch")).isEqualTo(1);
    }

    @Test
    void aCallbackWithoutAPartnerCodeIsAMerchantMismatch() {
        verifiedAs(verified(50_000L, null));
        when(lookup.find("momo", ORDER)).thenReturn(Optional.of(ORDER_50K));

        assertThat(handle()).isEqualTo(HttpStatus.NO_CONTENT);

        assertRejectedRow("merchant_mismatch");
    }

    @Test
    void anAmountThatDiffersFromTheRecordedOneIsAnAmountMismatch() {
        verifiedAs(verified(5_000L, "MOMOTEST"));
        when(lookup.find("momo", ORDER)).thenReturn(Optional.of(ORDER_50K));

        assertThat(handle()).isEqualTo(HttpStatus.NO_CONTENT);

        assertRejectedRow("amount_mismatch");
        assertThat(count("rejected", "amount_mismatch")).isEqualTo(1);
    }

    @Test
    void anAbsentAmountOnAPaymentCallbackIsAnAmountMismatch() {
        verifiedAs(verified(null, "MOMOTEST"));
        when(lookup.find("momo", ORDER)).thenReturn(Optional.of(ORDER_50K));

        assertThat(handle()).isEqualTo(HttpStatus.NO_CONTENT);

        assertRejectedRow("amount_mismatch");
    }

    @Test
    void aLookupThatThrowsIsAnswered503AndRejected() {
        verifiedAs(verified(50_000L, "MOMOTEST"));
        when(lookup.find(anyString(), anyString())).thenThrow(new IllegalStateException("down"));

        assertThat(handle()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        assertRejectedRow("cross_check_unavailable");
        assertThat(count("rejected", "cross_check_unavailable")).isEqualTo(1);
    }

    @Test
    void anUnavailableLookupBeanIsTreatedAsALookupFailure() {
        PaymentWebhookIngest withoutLookup = new PaymentWebhookIngest(new PaymentProviders(List.of(provider)),
                () -> null, repository, new WebhookTelemetry(meters));
        verifiedAs(verified(50_000L, "MOMOTEST"));

        assertThat(withoutLookup.handle(BODY, SOURCE, NOW)).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void aMatchingCallbackIsStoredAsAcceptedAndAnswered204() {
        PaymentProviderEvent event = event(50_000L);
        verifiedAs(new CallbackVerification.Verified(event, MAPPER.createObjectNode().put("partnerCode", "MOMOTEST"),
                REQUEST));
        when(lookup.find("momo", ORDER)).thenReturn(Optional.of(ORDER_50K));
        when(repository.insertAccepted("momo", REQUEST, event, BODY, SOURCE, NOW)).thenReturn(InsertResult.ACCEPTED);

        assertThat(handle()).isEqualTo(HttpStatus.NO_CONTENT);

        verify(repository, never()).insertRejected(any(), any(), any(), any(), any(), any());
        assertThat(count("accepted", "none")).isEqualTo(1);
        assertOneTimerSample();
    }

    @Test
    void aRepeatedRequestIdIsAnswered204AndCountedAsADuplicate() {
        verifiedAs(verified(50_000L, "MOMOTEST"));
        when(lookup.find("momo", ORDER)).thenReturn(Optional.of(ORDER_50K));
        when(repository.insertAccepted(eq("momo"), eq(REQUEST), any(), eq(BODY), eq(SOURCE), eq(NOW)))
                .thenReturn(InsertResult.DUPLICATE);

        assertThat(handle()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(count("duplicate", "none")).isEqualTo(1);
    }

    @Test
    void aFailedAcceptedWriteIsAnswered503() {
        verifiedAs(verified(50_000L, "MOMOTEST"));
        when(lookup.find("momo", ORDER)).thenReturn(Optional.of(ORDER_50K));
        when(repository.insertAccepted(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("database down"));

        assertThat(handle()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        assertThat(count("rejected", "write_failed")).isEqualTo(1);
    }

    @Test
    void aFailedRejectedRowWriteDoesNotChangeTheAnswer() {
        when(repository.insertRejected(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("database down"));
        verifiedAs(new CallbackVerification.Rejected("signature_mismatch", REQUEST));

        assertThat(handle()).isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(logLines.list).anySatisfy(line -> assertThat(line.getFormattedMessage())
                .startsWith("payment_webhook_rejected_row_failed"));
    }

    @Test
    void mixedTrafficIsCountedOncePerDispositionWithOneTimerSampleEach() {
        when(lookup.find("momo", ORDER)).thenReturn(Optional.of(ORDER_50K));
        when(repository.insertAccepted(any(), any(), any(), any(), any(), any()))
                .thenReturn(InsertResult.ACCEPTED, InsertResult.DUPLICATE);
        verifiedAs(verified(50_000L, "MOMOTEST"));
        handle();
        handle();
        verifiedAs(new CallbackVerification.Rejected("signature_mismatch", REQUEST));
        handle();

        assertThat(count("accepted", "none")).isEqualTo(1);
        assertThat(count("duplicate", "none")).isEqualTo(1);
        assertThat(count("rejected", "signature_mismatch")).isEqualTo(1);
        assertThat(meters.get("payment.webhook.callback.duration").timer().count()).isEqualTo(3);
    }

    @Test
    void theLogCarriesNoBodyValue() {
        verifiedAs(new CallbackVerification.Rejected("event_out_of_bounds", REQUEST));
        handle();

        assertThat(logLines.list).allSatisfy(line -> assertThat(line.getFormattedMessage())
                .doesNotContain(ORDER).doesNotContain("raw"));
    }
}
