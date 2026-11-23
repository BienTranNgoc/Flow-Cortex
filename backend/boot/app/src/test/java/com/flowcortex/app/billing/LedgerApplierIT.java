package com.flowcortex.app.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.billing.ledger.LedgerApplier;
import com.flowcortex.billing.ledger.OpenPayment;
import com.flowcortex.billing.ledger.OpenedPayment;
import com.flowcortex.billing.ledger.PaymentLedger;
import com.flowcortex.billing.payment.PaymentProviderEvent;
import com.flowcortex.billing.payment.PaymentProviderEvent.Kind;
import com.flowcortex.billing.payment.PaymentProviderEvent.Reference;
import com.flowcortex.billing.provider.PaymentInstrument;
import com.flowcortex.billing.webhook.PaymentWebhookEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class LedgerApplierIT extends AbstractPostgresBootTest {

    private static final String PROVIDER = "momo";
    private static final String MERCHANT = "MOMOBKUN20180529";
    private static final String UNKNOWN_ORDER = "fc_0123456789abcdef0123456789abcdef";
    private static final byte[] RAW = {'{', '}'};
    private static final Instant PAID_AT = Instant.parse("2026-11-20T10:00:05Z");
    // Older than anything another class could leave behind, so a pass takes this class's rows first.
    private static final Instant RECEIVED = Instant.now().minus(1, ChronoUnit.HOURS);

    private final UUID user = UUID.randomUUID();
    private final List<String> requestIds = new ArrayList<>();

    @Autowired
    private PaymentLedger ledger;

    @Autowired
    private PaymentWebhookEventRepository inbox;

    @Autowired
    private LedgerApplier applier;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meters;

    @AfterEach
    void clean() {
        dropFailure();
        for (String requestId : requestIds) {
            jdbc.update("DELETE FROM payment_webhook_event WHERE provider_request_id = ?", requestId);
        }
        jdbc.update("DELETE FROM payment_transaction_event WHERE transaction_id IN "
                + "(SELECT id FROM payment_transaction WHERE user_id = ?)", user);
        jdbc.update("DELETE FROM payment_transaction WHERE user_id = ?", user);
    }

    private OpenedPayment open() {
        return ledger.open(new OpenPayment(user, "topup", "pkg-50k", "Top-up", 50_000L, PROVIDER, MERCHANT,
                Optional.of(PaymentInstrument.WALLET)));
    }

    private static PaymentProviderEvent event(String orderRef, String code, Long amount, Instant at) {
        return new PaymentProviderEvent("1", PROVIDER, Kind.INBOUND_PAYMENT_CALLBACK,
                new Reference(orderRef, UUID.randomUUID().toString(), "4088878653"), code, "Message", amount, at,
                "qr", at);
    }

    private UUID accept(PaymentProviderEvent event, Instant receivedAt) {
        String requestId = UUID.randomUUID().toString();
        requestIds.add(requestId);
        inbox.insertAccepted(PROVIDER, requestId, event, RAW, "118.69.210.244", receivedAt);
        return jdbc.queryForObject("SELECT id FROM payment_webhook_event WHERE provider = ? "
                + "AND provider_request_id = ?", UUID.class, PROVIDER, requestId);
    }

    private Map<String, Object> callback(UUID id) {
        return jdbc.queryForMap("SELECT applied_at, application_outcome, application_reason, transaction_id, "
                + "apply_attempts, next_attempt_at FROM payment_webhook_event WHERE id = ?", id);
    }

    private Map<String, Object> payment(UUID id) {
        return jdbc.queryForMap("SELECT status, provider_result_code, provider_transaction_id, "
                + "provider_status_at, succeeded_at FROM payment_transaction WHERE id = ?", id);
    }

    private List<Map<String, Object>> history(UUID id) {
        return jdbc.queryForList("SELECT from_status, to_status, reason, provider_result_code, evidence_ref, "
                + "provider_timestamp FROM payment_transaction_event WHERE transaction_id = ? "
                + "ORDER BY occurred_at, id", id);
    }

    private long movesFromPending(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM payment_transaction_event "
                + "WHERE transaction_id = ? AND from_status = 'pending'", Long.class, id);
    }

    private double count(String name) {
        return meters.find(name).counters().stream().mapToDouble(Counter::count).sum();
    }

    private void failHistoryInsertsFor(UUID callbackId) {
        jdbc.execute("CREATE OR REPLACE FUNCTION it_ledger_fail() RETURNS trigger LANGUAGE plpgsql AS $f$ BEGIN "
                + "IF NEW.evidence_ref = '" + callbackId + "' THEN RAISE EXCEPTION 'ledger it failure'; END IF; "
                + "RETURN NEW; END $f$");
        jdbc.execute("DROP TRIGGER IF EXISTS it_ledger_fail ON payment_transaction_event");
        jdbc.execute("CREATE TRIGGER it_ledger_fail BEFORE INSERT ON payment_transaction_event "
                + "FOR EACH ROW EXECUTE FUNCTION it_ledger_fail()");
    }

    private void dropFailure() {
        jdbc.execute("DROP TRIGGER IF EXISTS it_ledger_fail ON payment_transaction_event");
        jdbc.execute("DROP FUNCTION IF EXISTS it_ledger_fail()");
    }

    @Test
    void aSuccessCallbackMovesThePendingPaymentToSucceededWithItsFactsAndOneHistoryEntry() {
        OpenedPayment opened = open();
        UUID callbackId = accept(event(opened.orderRef(), "0", 50_000L, PAID_AT), RECEIVED);

        applier.applyPending();

        Map<String, Object> row = payment(opened.transactionId());
        assertThat(row).containsEntry("status", "succeeded")
                .containsEntry("provider_result_code", "0")
                .containsEntry("provider_transaction_id", "4088878653");
        assertThat(((Timestamp) row.get("provider_status_at")).toInstant()).isEqualTo(PAID_AT);
        assertThat(row.get("succeeded_at")).isNotNull();
        List<Map<String, Object>> entries = history(opened.transactionId());
        assertThat(entries).hasSize(2);
        assertThat(entries.get(1)).containsEntry("from_status", "pending")
                .containsEntry("to_status", "succeeded")
                .containsEntry("reason", "provider_code:0")
                .containsEntry("provider_result_code", "0")
                .containsEntry("evidence_ref", callbackId.toString());
        Map<String, Object> decided = callback(callbackId);
        assertThat(decided).containsEntry("application_outcome", "applied")
                .containsEntry("transaction_id", opened.transactionId());
        assertThat(decided.get("applied_at")).isNotNull();
    }

    @Test
    void aRedeliveryAfterSuccessIsSkippedAndAddsNoHistory() {
        OpenedPayment opened = open();
        accept(event(opened.orderRef(), "0", 50_000L, PAID_AT), RECEIVED);
        applier.applyPending();
        UUID redelivery = accept(event(opened.orderRef(), "0", 50_000L, PAID_AT.plusSeconds(30)),
                RECEIVED.plusSeconds(1));

        applier.applyPending();

        assertThat(callback(redelivery)).containsEntry("application_outcome", "skipped")
                .containsEntry("application_reason", "already_in_state");
        assertThat(history(opened.transactionId())).hasSize(2);
        assertThat(payment(opened.transactionId())).containsEntry("status", "succeeded");
    }

    @Test
    void aStoredEventThatCannotBeReadIsRefusedAndNotTakenAgain() {
        OpenedPayment opened = open();
        UUID callbackId = accept(event(opened.orderRef(), "0", 50_000L, PAID_AT), RECEIVED);
        jdbc.update("UPDATE payment_webhook_event SET event = '{\"x\":1}'::jsonb WHERE id = ?", callbackId);

        applier.applyPending();
        Object appliedAt = callback(callbackId).get("applied_at");
        applier.applyPending();

        assertThat(callback(callbackId)).containsEntry("application_outcome", "refused")
                .containsEntry("application_reason", "event_unreadable")
                .containsEntry("transaction_id", null)
                .containsEntry("apply_attempts", 0)
                .containsEntry("applied_at", appliedAt);
        assertThat(payment(opened.transactionId())).containsEntry("status", "pending");
    }

    @Test
    void aSuccessForAnUnknownOrderIsRefusedAndCountedAsUnappliedMoney() {
        double before = count("payment.ledger.unapplied_money");
        UUID callbackId = accept(event(UNKNOWN_ORDER, "0", 50_000L, PAID_AT), RECEIVED);

        applier.applyPending();

        assertThat(callback(callbackId)).containsEntry("application_outcome", "refused")
                .containsEntry("application_reason", "transaction_not_found")
                .containsEntry("transaction_id", null);
        assertThat(count("payment.ledger.unapplied_money")).isEqualTo(before + 1);
    }

    @Test
    void aCallbackOfAnotherProviderDoesNotFindThePayment() {
        OpenedPayment opened = open();
        UUID callbackId = accept(event(opened.orderRef(), "1006", 50_000L, PAID_AT), RECEIVED);
        jdbc.update("UPDATE payment_webhook_event SET provider = 'other_it' WHERE id = ?", callbackId);

        applier.applyPending();

        assertThat(callback(callbackId)).containsEntry("application_outcome", "refused")
                .containsEntry("application_reason", "transaction_not_found");
        assertThat(payment(opened.transactionId())).containsEntry("status", "pending");
    }

    @Test
    void aSuccessAfterFailureLeavesThePaymentFailedAndCountsUnappliedMoney() {
        OpenedPayment opened = open();
        accept(event(opened.orderRef(), "1006", 50_000L, PAID_AT), RECEIVED);
        applier.applyPending();
        double before = count("payment.ledger.unapplied_money");
        UUID late = accept(event(opened.orderRef(), "0", 50_000L, PAID_AT.plusSeconds(5)), RECEIVED.plusSeconds(1));

        applier.applyPending();

        assertThat(payment(opened.transactionId())).containsEntry("status", "failed");
        assertThat(callback(late)).containsEntry("application_outcome", "refused")
                .containsEntry("application_reason", "undeclared_transition")
                .containsEntry("transaction_id", opened.transactionId());
        assertThat(count("payment.ledger.unapplied_money")).isEqualTo(before + 1);
    }

    @Test
    void twoPassesAtOnceApplyOneSuccessCallbackOnce() throws Exception {
        OpenedPayment opened = open();
        UUID callbackId = accept(event(opened.orderRef(), "0", 50_000L, PAID_AT), RECEIVED);

        runTwoPassesTogether();

        assertThat(movesFromPending(opened.transactionId())).isEqualTo(1);
        assertThat(history(opened.transactionId())).hasSize(2);
        assertThat(callback(callbackId)).containsEntry("application_outcome", "applied");
    }

    @Test
    void aFailureAndASuccessCallbackForOnePaymentMoveItOnceWhateverTheOrder() throws Exception {
        OpenedPayment opened = open();
        UUID declined = accept(event(opened.orderRef(), "1006", 50_000L, PAID_AT), RECEIVED);
        UUID paid = accept(event(opened.orderRef(), "0", 50_000L, PAID_AT), RECEIVED.plusMillis(1));

        runTwoPassesTogether();

        assertThat(movesFromPending(opened.transactionId())).isEqualTo(1);
        List<Object> outcomes = List.of(callback(declined).get("application_outcome"),
                callback(paid).get("application_outcome"));
        assertThat(outcomes).containsExactlyInAnyOrder("applied", "refused");
        assertThat(callback(declined).get("applied_at")).isNotNull();
        assertThat(callback(paid).get("applied_at")).isNotNull();
    }

    private void runTwoPassesTogether() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> passes = List.of(pool.submit(() -> pass(go)), pool.submit(() -> pass(go)));
            go.countDown();
            for (Future<?> pass : passes) {
                pass.get();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private Object pass(CountDownLatch go) throws InterruptedException {
        go.await();
        applier.applyPending();
        return null;
    }

    @Test
    void aCallbackThatFailsIsRetriedLaterWhileTheOnesBehindItAreApplied() {
        OpenedPayment first = open();
        OpenedPayment second = open();
        OpenedPayment third = open();
        UUID failing = accept(event(first.orderRef(), "0", 50_000L, PAID_AT), RECEIVED);
        UUID behind = accept(event(second.orderRef(), "0", 50_000L, PAID_AT), RECEIVED.plusSeconds(1));
        UUID last = accept(event(third.orderRef(), "0", 50_000L, PAID_AT), RECEIVED.plusSeconds(2));
        failHistoryInsertsFor(failing);
        double failuresBefore = count("payment.ledger.apply.failure");
        Instant started = Instant.now();

        applier.applyPending();

        assertThat(payment(first.transactionId())).containsEntry("status", "pending");
        assertThat(history(first.transactionId())).hasSize(1);
        Map<String, Object> retried = callback(failing);
        assertThat(retried).containsEntry("apply_attempts", 1).containsEntry("applied_at", null);
        Instant next = ((Timestamp) retried.get("next_attempt_at")).toInstant();
        assertThat(next).isBetween(started.plusSeconds(25), Instant.now().plusSeconds(35));
        assertThat(count("payment.ledger.apply.failure")).isEqualTo(failuresBefore + 1);
        assertThat(callback(behind)).containsEntry("application_outcome", "applied");
        assertThat(callback(last)).containsEntry("application_outcome", "applied");

        applier.applyPending();
        assertThat(callback(failing)).containsEntry("apply_attempts", 1).containsEntry("applied_at", null);

        dropFailure();
        jdbc.update("UPDATE payment_webhook_event SET next_attempt_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), failing);
        applier.applyPending();

        assertThat(callback(failing)).containsEntry("application_outcome", "applied");
        assertThat(payment(first.transactionId())).containsEntry("status", "succeeded");
    }

    @Test
    void aCallbackOnItsLastAttemptIsRefusedAsExhaustedAndCountedAsUnappliedMoney() {
        OpenedPayment opened = open();
        UUID failing = accept(event(opened.orderRef(), "0", 50_000L, PAID_AT), RECEIVED);
        jdbc.update("UPDATE payment_webhook_event SET apply_attempts = 9 WHERE id = ?", failing);
        failHistoryInsertsFor(failing);
        double before = count("payment.ledger.unapplied_money");

        applier.applyPending();
        applier.applyPending();

        assertThat(callback(failing)).containsEntry("application_outcome", "refused")
                .containsEntry("application_reason", "apply_exhausted")
                .containsEntry("apply_attempts", 9);
        assertThat(payment(opened.transactionId())).containsEntry("status", "pending");
        assertThat(count("payment.ledger.unapplied_money")).isEqualTo(before + 1);
    }

    @Test
    void onePassAppliesAtMostTheBatchSize() {
        for (int i = 0; i < 250; i++) {
            accept(event(UNKNOWN_ORDER, "1006", 50_000L, PAID_AT), RECEIVED.plusMillis(i));
        }

        applier.applyPending();

        assertThat(decidedOfThisClass()).isEqualTo(100);
    }

    @Test
    void aSwitchedOffApplierAppliesNothing() {
        OpenedPayment opened = open();
        UUID callbackId = accept(event(opened.orderRef(), "0", 50_000L, PAID_AT), RECEIVED);

        applier.scheduled();

        assertThat(callback(callbackId)).containsEntry("applied_at", null);
        assertThat(payment(opened.transactionId())).containsEntry("status", "pending");
    }

    private long decidedOfThisClass() {
        long decided = 0;
        for (String requestId : requestIds) {
            decided += jdbc.queryForObject("SELECT count(*) FROM payment_webhook_event "
                    + "WHERE provider_request_id = ? AND applied_at IS NOT NULL", Long.class, requestId);
        }
        return decided;
    }
}
