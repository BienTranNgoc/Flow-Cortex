package com.flowcortex.billing.ledger;

import com.flowcortex.billing.ledger.LedgerDecision.Applied;
import com.flowcortex.billing.ledger.LedgerDecision.Decision;
import com.flowcortex.billing.ledger.LedgerDecision.Refused;
import com.flowcortex.billing.ledger.LedgerDecision.Skipped;
import com.flowcortex.billing.payment.PaymentProviderEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * What the applier reports. Tags are fixed words, provider keys and exception class names; log lines carry
 * the callback id, the payment id, the reason, the provider's result code and the problem owner, and never
 * an amount, a user id, a provider message or a payload value.
 */
@Component
class LedgerTelemetry {

    private static final Logger LOG = LoggerFactory.getLogger(LedgerTelemetry.class);
    private static final Pattern SAFE_CODE = Pattern.compile("[A-Za-z0-9_.-]{1,32}");
    private static final String NONE = "none";
    static final String EXHAUSTED = "apply_exhausted";

    private final MeterRegistry meters;

    LedgerTelemetry(MeterRegistry meters) {
        this.meters = meters;
    }

    /** Counts a decision that was committed, and logs a refusal. */
    void decided(UUID callbackId, String provider, Decision decision, Optional<PaymentProviderEvent> event,
            UUID paymentId) {
        switch (decision) {
            case Applied applied -> counted(provider, "applied", NONE);
            case Skipped skipped -> counted(provider, "skipped", skipped.reason());
            case Refused refused -> {
                counted(provider, "refused", refused.reason());
                refusal(callbackId, provider, refused.reason(), refused.derivedSuccess(), event, paymentId);
            }
        }
    }

    /** A callback given up on after its last attempt. */
    void exhausted(UUID callbackId, String provider, Optional<PaymentProviderEvent> event) {
        counted(provider, "refused", EXHAUSTED);
        boolean success = event.isPresent()
                && MoneyDerivation.meansSuccess(event.get().provider(), event.get().resultCode());
        refusal(callbackId, provider, EXHAUSTED, success, event, null);
    }

    /** One application attempt failed for a reason that is not a refusal. */
    void applyFailed(UUID callbackId, Throwable error) {
        String type = error.getClass().getSimpleName();
        meters.counter("payment.ledger.apply.failure", "error", type).increment();
        LOG.error("Ledger apply failed: event=payment.ledger.apply.failure callback_id={} error={}",
                callbackId == null ? NONE : callbackId, type);
    }

    void pass(String outcome, Duration elapsed) {
        Timer.builder("payment.ledger.apply.pass").tag("outcome", outcome).register(meters).record(elapsed);
    }

    private void counted(String provider, String outcome, String reason) {
        meters.counter("payment.ledger.event", "provider", provider, "outcome", outcome, "reason", reason)
                .increment();
    }

    private void refusal(UUID callbackId, String provider, String reason, boolean unappliedMoney,
            Optional<PaymentProviderEvent> event, UUID paymentId) {
        String code = event.map(PaymentProviderEvent::resultCode).map(LedgerTelemetry::safe).orElse(NONE);
        String owner = event.flatMap(LedgerTelemetry::ownerOf).orElse("unknown");
        Object payment = paymentId == null ? NONE : paymentId;
        if (unappliedMoney) {
            meters.counter("payment.ledger.unapplied_money", "provider", provider, "reason", reason).increment();
            LOG.error("Ledger unapplied money: event=payment.ledger.unapplied_money callback_id={} payment_id={}"
                    + " reason={} code={} owner={}", callbackId, payment, reason, code, owner);
        } else {
            LOG.warn("Ledger refusal: event=payment.ledger.refused callback_id={} payment_id={} reason={} code={}"
                    + " owner={}", callbackId, payment, reason, code, owner);
        }
    }

    private static Optional<String> ownerOf(PaymentProviderEvent event) {
        return Optional.ofNullable(event.resultCode())
                .flatMap(code -> ProviderCodeTables.forProvider(event.provider()).flatMap(table -> table.apply(code)))
                .map(ProviderCodeTables.CodeRule::owner);
    }

    private static String safe(String code) {
        return SAFE_CODE.matcher(code).matches() ? code : "invalid";
    }
}
