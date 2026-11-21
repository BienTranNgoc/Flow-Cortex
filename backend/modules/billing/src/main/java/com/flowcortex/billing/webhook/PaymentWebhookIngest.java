package com.flowcortex.billing.webhook;

import com.flowcortex.billing.payment.PaymentProviderEvent;
import com.flowcortex.billing.provider.CallbackVerification;
import com.flowcortex.billing.provider.PaymentOrderLookup;
import com.flowcortex.billing.provider.PaymentOrderLookup.RecordedOrder;
import com.flowcortex.billing.provider.PaymentProvider;
import com.flowcortex.billing.provider.PaymentProviders;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Turns one callback into a recorded row and a status: verification over the raw bytes, our reference shape, the
 * cross-check against the recorded order, then the write. A refusal worth keeping as evidence is stored as a
 * rejected row; the answer is 401 for a callback that failed verification, 503 where a retry can succeed, and
 * 204 otherwise.
 */
@Component
class PaymentWebhookIngest {

    static final String PROVIDER = "momo";

    private static final String REFERENCE_NOT_OURS = "reference_not_ours";
    private static final String EVENT_OUT_OF_BOUNDS = "event_out_of_bounds";
    private static final String SIGNATURE_MISMATCH = "signature_mismatch";

    private final PaymentProviders providers;
    private final Supplier<PaymentOrderLookup> lookup;
    private final PaymentWebhookEventRepository repository;
    private final WebhookTelemetry telemetry;

    @Autowired
    PaymentWebhookIngest(PaymentProviders providers, ObjectProvider<PaymentOrderLookup> lookup,
            PaymentWebhookEventRepository repository, WebhookTelemetry telemetry) {
        this(providers, lookup::getIfAvailable, repository, telemetry);
    }

    PaymentWebhookIngest(PaymentProviders providers, Supplier<PaymentOrderLookup> lookup,
            PaymentWebhookEventRepository repository, WebhookTelemetry telemetry) {
        this.providers = providers;
        this.lookup = lookup;
        this.repository = repository;
        this.telemetry = telemetry;
    }

    HttpStatus handle(byte[] body, String remoteAddress, Instant receivedAt) {
        long started = System.nanoTime();
        Outcome outcome = decide(body, remoteAddress, receivedAt);
        telemetry.handled(PROVIDER, outcome.disposition, outcome.reason, Duration.ofNanos(System.nanoTime() - started));
        return outcome.status;
    }

    private record Outcome(HttpStatus status, String disposition, String reason) {
    }

    private Outcome decide(byte[] body, String remoteAddress, Instant receivedAt) {
        Optional<PaymentProvider> provider = providers.find(PROVIDER);
        if (provider.isEmpty()) {
            return new Outcome(HttpStatus.NOT_FOUND, WebhookTelemetry.DISABLED, "provider_disabled");
        }
        CallbackVerification verification = provider.get().verifyCallback(body);
        return switch (verification) {
            case CallbackVerification.Rejected rejected -> refuse(rejected, body, remoteAddress, receivedAt);
            case CallbackVerification.Verified verified -> crossCheckAndStore(verified, body, remoteAddress, receivedAt);
        };
    }

    private Outcome refuse(CallbackVerification.Rejected rejected, byte[] body, String remoteAddress, Instant receivedAt) {
        String reason = rejected.reason();
        if (SIGNATURE_MISMATCH.equals(reason)) {
            telemetry.signatureMismatch(PROVIDER);
        }
        boolean verified = REFERENCE_NOT_OURS.equals(reason) || EVENT_OUT_OF_BOUNDS.equals(reason);
        Optional<UUID> row = recordRejected(rejected.requestId(), reason, body, remoteAddress, receivedAt);
        if (EVENT_OUT_OF_BOUNDS.equals(reason)) {
            telemetry.outOfBounds(PROVIDER, row.map(UUID::toString).orElse("unwritten"));
        }
        if (REFERENCE_NOT_OURS.equals(reason)) {
            telemetry.referenceNotOurs(PROVIDER, row.map(UUID::toString).orElse("unwritten"));
        }
        return new Outcome(verified ? HttpStatus.NO_CONTENT : HttpStatus.UNAUTHORIZED, WebhookTelemetry.REJECTED, reason);
    }

    private Outcome crossCheckAndStore(CallbackVerification.Verified verified, byte[] body, String remoteAddress,
            Instant receivedAt) {
        PaymentProviderEvent event = verified.event();
        String requestId = verified.requestId() != null ? verified.requestId() : "unidentified:" + UUID.randomUUID();

        Optional<RecordedOrder> order;
        try {
            PaymentOrderLookup orders = lookup.get();
            if (orders == null) {
                throw new IllegalStateException("no order lookup is available");
            }
            order = orders.find(PROVIDER, event.reference().operationRef());
        } catch (RuntimeException e) {
            recordRejected(requestId, "cross_check_unavailable", body, remoteAddress, receivedAt);
            return new Outcome(HttpStatus.SERVICE_UNAVAILABLE, WebhookTelemetry.REJECTED, "cross_check_unavailable");
        }
        String mismatch = mismatch(order, verified.payload(), event);
        if (mismatch != null) {
            recordRejected(requestId, mismatch, body, remoteAddress, receivedAt);
            return new Outcome(HttpStatus.NO_CONTENT, WebhookTelemetry.REJECTED, mismatch);
        }
        try {
            return switch (repository.insertAccepted(PROVIDER, requestId, event, body, remoteAddress, receivedAt)) {
                case ACCEPTED -> new Outcome(HttpStatus.NO_CONTENT, WebhookTelemetry.ACCEPTED, null);
                case DUPLICATE -> new Outcome(HttpStatus.NO_CONTENT, WebhookTelemetry.DUPLICATE, null);
            };
        } catch (RuntimeException e) {
            telemetry.writeFailed(PROVIDER, e);
            return new Outcome(HttpStatus.SERVICE_UNAVAILABLE, WebhookTelemetry.REJECTED, "write_failed");
        }
    }

    /** The rejection reason when the callback disagrees with what was recorded at opening, else null. */
    private static String mismatch(Optional<RecordedOrder> order, JsonNode payload, PaymentProviderEvent event) {
        if (order.isEmpty()) {
            return "order_not_found";
        }
        JsonNode partner = payload.get("partnerCode");
        if (partner == null || !partner.isString() || !partner.asString().equals(order.get().merchantCode())) {
            return "merchant_mismatch";
        }
        if (event.amount() == null || event.amount() != order.get().amountVnd()) {
            return "amount_mismatch";
        }
        return null;
    }

    /** A failed evidence write is logged and never changes the answer. */
    private Optional<UUID> recordRejected(String requestId, String reason, byte[] body, String remoteAddress,
            Instant receivedAt) {
        String id = requestId != null ? requestId : "unidentified:" + UUID.randomUUID();
        try {
            return Optional.of(repository.insertRejected(PROVIDER, id, reason, body, remoteAddress, receivedAt));
        } catch (RuntimeException e) {
            telemetry.rejectedRowFailed(PROVIDER, reason);
            return Optional.empty();
        }
    }
}
