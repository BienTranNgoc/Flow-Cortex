package com.flowcortex.billing.receipt;

import com.flowcortex.billing.receipt.ReceiptDeliveryRepository.Claim;
import com.flowcortex.billing.receipt.ReceiptDeliveryRepository.Due;
import com.flowcortex.billing.receipt.ReceiptRetry.RetryAt;
import com.flowcortex.jobs.api.ClusterJob;
import com.flowcortex.mail.api.MailOutcome;
import com.flowcortex.mail.api.Mailer;
import com.flowcortex.mail.api.OutgoingMail;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Sends the receipt mail of every collected payment: claims a due row, builds and sends the mail, and marks the
 * row. A failed send is tried again 30 minutes later and given up after the sixth attempt. Nothing holds a
 * transaction across the send; a pass whose send outlived its lease finds its claim gone and changes nothing.
 * Without the app's public URL, which the mail links to, a pass sends nothing and counts no attempt.
 */
@Component
public class ReceiptDeliveryJob implements ClusterJob {

    private static final Logger LOG = LoggerFactory.getLogger(ReceiptDeliveryJob.class);
    private static final Duration LEASE = Duration.ofMinutes(10);
    private static final int BATCH = 200;

    private final ReceiptDeliveryRepository deliveries;
    private final ReceiptMail receiptMail;
    private final Mailer mailer;
    private final ReceiptTelemetry telemetry;
    private final Clock clock;
    private final String publicUrl;

    @Autowired
    ReceiptDeliveryJob(ReceiptDeliveryRepository deliveries, ReceiptMail receiptMail, Mailer mailer,
            ReceiptTelemetry telemetry, Clock clock, @Value("${flowcortex.app.public-url:}") String publicUrl) {
        this.deliveries = deliveries;
        this.receiptMail = receiptMail;
        this.mailer = mailer;
        this.telemetry = telemetry;
        this.clock = clock;
        this.publicUrl = publicUrl == null ? "" : publicUrl;
    }

    @Override
    public String name() {
        return "billing.receipt-delivery";
    }

    @Override
    public Duration interval() {
        return Duration.ofMinutes(1);
    }

    @Override
    public Duration lease() {
        return LEASE;
    }

    @Override
    public void run() {
        runAt(clock.instant());
    }

    /** One pass as of {@code now}. */
    public void runAt(Instant now) {
        if (publicUrl.isBlank()) {
            telemetry.paused();
            LOG.warn("billing.receipt.delivery outcome=paused reason=public_url_unset");
            return;
        }
        Instant leaseStart = now.minus(LEASE);
        for (Due due : deliveries.due(now, leaseStart, BATCH)) {
            try {
                deliver(due, now, leaseStart);
            } catch (RuntimeException e) {
                // The claim stays and expires with its lease; only the class name is logged, as an exception can
                // carry an address.
                LOG.error("billing.receipt.delivery outcome=error transaction={} error={}", due.transactionId(),
                        e.getClass().getName());
            }
        }
    }

    private void deliver(Due due, Instant now, Instant leaseStart) {
        Optional<Claim> held = deliveries.claim(due.transactionId(), now, leaseStart);
        if (held.isEmpty()) {
            return;
        }
        Claim claim = held.get();
        Optional<OutgoingMail> mail;
        try {
            mail = receiptMail.build(due.userId(), due.transactionId(), publicUrl);
        } catch (RuntimeException e) {
            failed(claim, now, e);
            return;
        }
        if (mail.isEmpty()) {
            if (deliveries.markNoAddress(claim)) {
                telemetry.noAddress();
                LOG.info("billing.receipt.delivery outcome=no_address transaction={}", claim.transactionId());
            } else {
                claimLost(claim);
            }
            return;
        }
        MailOutcome outcome;
        try {
            outcome = mailer.send(mail.get());
        } catch (RuntimeException e) {
            failed(claim, now, e);
            return;
        }
        if (outcome == MailOutcome.FAILED) {
            failed(claim, now, null);
        } else if (deliveries.markSent(claim, now)) {
            telemetry.sent();
            LOG.info("billing.receipt.delivery outcome=sent transaction={} attempts={}", claim.transactionId(),
                    claim.attempts());
        } else {
            claimLost(claim);
        }
    }

    private void failed(Claim claim, Instant now, RuntimeException cause) {
        String error = cause == null ? "none" : cause.getClass().getName();
        if (ReceiptRetry.after(claim.attempts()) instanceof RetryAt retry) {
            if (deliveries.release(claim, now.plus(retry.delay()))) {
                telemetry.retry();
                LOG.warn("billing.receipt.delivery outcome=retry transaction={} attempts={} error={}",
                        claim.transactionId(), claim.attempts(), error);
            } else {
                claimLost(claim);
            }
        } else if (deliveries.markFailed(claim)) {
            telemetry.failed();
            LOG.error("billing.receipt.delivery outcome=failed transaction={} attempts={} error={}",
                    claim.transactionId(), claim.attempts(), error);
        } else {
            claimLost(claim);
        }
    }

    private void claimLost(Claim claim) {
        LOG.info("billing.receipt.delivery outcome=claim_lost transaction={}", claim.transactionId());
    }
}
