package com.flowcortex.app.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.billing.api.PaymentStatusChangedEvent;
import com.flowcortex.billing.ledger.LedgerApplier;
import com.flowcortex.billing.ledger.OpenPayment;
import com.flowcortex.billing.ledger.OpenedPayment;
import com.flowcortex.billing.ledger.PaymentLedger;
import com.flowcortex.billing.payment.PaymentProviderEvent;
import com.flowcortex.billing.payment.PaymentProviderEvent.Kind;
import com.flowcortex.billing.payment.PaymentProviderEvent.Reference;
import com.flowcortex.billing.provider.CircuitState;
import com.flowcortex.billing.provider.PaymentInstrument;
import com.flowcortex.billing.provider.ProviderCallOutcome;
import com.flowcortex.billing.provider.ProviderCircuitOpenException;
import com.flowcortex.billing.provider.ProviderTransportException;
import com.flowcortex.billing.reconcile.AgeingReconcileJob;
import com.flowcortex.billing.reconcile.DailyReconcileJob;
import com.flowcortex.billing.webhook.PaymentWebhookEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Both reconciliation passes against a real database and a scripted provider: what they ask, how the answer
 * moves the payment, what they record, and what a failure or a race leaves behind.
 */
@SpringBootTest(properties = {
        "flowcortex.billing.reconcile.ageing.enabled=true",
        "flowcortex.billing.reconcile.daily.enabled=true"})
@ExtendWith(OutputCaptureExtension.class)
@Import(PaymentReconciliationIT.Wiring.class)
class PaymentReconciliationIT extends AbstractPostgresBootTest {

    private static final String PROVIDER = "momo";
    private static final String MERCHANT = "MOMOBKUN20180529";
    private static final byte[] RAW = {'{', '}'};
    private static final List<PaymentStatusChangedEvent> EVENTS = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class Wiring {

        @Bean
        ScriptedPaymentProvider scriptedMomo() {
            return new ScriptedPaymentProvider();
        }

        @Bean
        StatusListener statusListener() {
            return new StatusListener();
        }
    }

    static class StatusListener {

        @TransactionalEventListener
        void on(PaymentStatusChangedEvent event) {
            EVENTS.add(event);
        }
    }

    private final UUID user = UUID.randomUUID();
    private final List<String> requestIds = new ArrayList<>();

    @Autowired
    private PaymentLedger ledger;

    @Autowired
    private AgeingReconcileJob ageing;

    @Autowired
    private DailyReconcileJob daily;

    @Autowired
    private LedgerApplier applier;

    @Autowired
    private PaymentWebhookEventRepository inbox;

    @Autowired
    private ScriptedPaymentProvider provider;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meters;

    @BeforeEach
    void reset() {
        provider.reset();
        EVENTS.clear();
    }

    @AfterEach
    void clean() {
        jdbc.execute("DROP TRIGGER IF EXISTS it_reconcile_fail ON payment_drift_record");
        jdbc.execute("DROP FUNCTION IF EXISTS it_reconcile_fail()");
        jdbc.execute("DROP TRIGGER IF EXISTS it_reconcile_twin ON payment_drift_record");
        jdbc.execute("DROP FUNCTION IF EXISTS it_reconcile_twin()");
        jdbc.execute("DROP TRIGGER IF EXISTS it_reconcile_enrol ON payment_drift_record");
        jdbc.execute("DROP FUNCTION IF EXISTS it_reconcile_enrol()");
        String mine = "(SELECT id FROM payment_transaction WHERE user_id = ?)";
        jdbc.update("DELETE FROM payment_drift_record WHERE transaction_id IN " + mine, user);
        jdbc.update("DELETE FROM payment_reconciliation_schedule WHERE transaction_id IN " + mine, user);
        for (String requestId : requestIds) {
            jdbc.update("DELETE FROM payment_webhook_event WHERE provider_request_id = ?", requestId);
        }
        jdbc.update("DELETE FROM payment_transaction_event WHERE transaction_id IN " + mine, user);
        jdbc.update("DELETE FROM event_publication WHERE event_type = ? AND serialized_event LIKE ?",
                PaymentStatusChangedEvent.class.getName(), "%" + user + "%");
        jdbc.update("DELETE FROM payment_transaction WHERE user_id = ?", user);
    }

    private OpenedPayment open(Duration age) {
        OpenedPayment opened = ledger.open(new OpenPayment(user, "topup", "pkg-50k", "Top-up", 50_000L, PROVIDER,
                MERCHANT, Optional.of(PaymentInstrument.WALLET)));
        jdbc.update("UPDATE payment_transaction SET created_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(age)), opened.transactionId());
        return opened;
    }

    private void setStatus(UUID id, String status) {
        jdbc.update("UPDATE payment_transaction SET status = ?, succeeded_at = CASE WHEN ? = 'succeeded' "
                + "THEN now() ELSE succeeded_at END WHERE id = ?", status, status, id);
    }

    private Map<String, Object> payment(UUID id) {
        return jdbc.queryForMap("SELECT status, provider_result_code, succeeded_at, reconciled_at "
                + "FROM payment_transaction WHERE id = ?", id);
    }

    private List<Map<String, Object>> schedule(UUID id) {
        return jdbc.queryForList("SELECT attempts, last_swept_at, next_sweep_at, resolved_at, escalated_at, "
                + "last_outcome, escalation_reason, initiated_at FROM payment_reconciliation_schedule "
                + "WHERE transaction_id = ?", id);
    }

    private Map<String, Object> row(UUID id) {
        List<Map<String, Object>> rows = schedule(id);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private List<Map<String, Object>> drifts(UUID id) {
        return jdbc.queryForList("SELECT drift_class, pass, provider, our_status, provider_status, "
                + "provider_result_code, our_amount_vnd, provider_amount_vnd, evidence_ref, resolution, "
                + "needs_person FROM payment_drift_record WHERE transaction_id = ?", id);
    }

    private List<Map<String, Object>> history(UUID id) {
        return jdbc.queryForList("SELECT from_status, to_status, reason, evidence_ref FROM payment_transaction_event "
                + "WHERE transaction_id = ? ORDER BY occurred_at, id", id);
    }

    private double count(String name, String... tags) {
        return meters.find(name).tags(tags).counters().stream().mapToDouble(Counter::count).sum();
    }

    private long passes(String pass, String outcome) {
        return meters.find("payment.reconcile.pass").tags("pass", pass, "outcome", outcome).timers().stream()
                .mapToLong(timer -> timer.count()).sum();
    }

    private List<PaymentStatusChangedEvent> eventsOf(UUID id) {
        return EVENTS.stream().filter(event -> event.transactionId().equals(id)).toList();
    }

    private long publicationsOf(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM event_publication WHERE event_type = ? "
                + "AND serialized_event LIKE ?", Long.class, PaymentStatusChangedEvent.class.getName(), "%" + id + "%");
    }

    private void makeDueNow(UUID id) {
        jdbc.update("UPDATE payment_reconciliation_schedule SET next_sweep_at = now() - interval '1 minute' "
                + "WHERE transaction_id = ?", id);
    }

    @Test
    void aPendingPaymentOldEnoughIsEnrolledOnceWithNoAsksSpent() {
        OpenedPayment opened = open(Duration.ofMinutes(20));
        Instant retryAt = Instant.now().plus(Duration.ofMinutes(5));
        provider.script(query -> {
            throw new ProviderCircuitOpenException(PROVIDER, "query", CircuitState.OPEN, retryAt);
        });

        ageing.run();
        makeDueNow(opened.transactionId());
        ageing.run();

        Map<String, Object> scheduled = row(opened.transactionId());
        assertThat(scheduled).containsEntry("attempts", 0);
        Instant initiated = ((Timestamp) scheduled.get("initiated_at")).toInstant();
        assertThat(initiated).isBefore(Instant.now().minus(Duration.ofMinutes(19)));
    }

    @Test
    void aPaymentTooYoungOrAlreadySettledIsNotEnrolled() {
        OpenedPayment young = open(Duration.ofMinutes(10));
        OpenedPayment settled = open(Duration.ofMinutes(20));
        setStatus(settled.transactionId(), "succeeded");

        ageing.run();

        assertThat(schedule(young.transactionId())).isEmpty();
        assertThat(schedule(settled.transactionId())).isEmpty();
    }

    @Test
    void aLostCallbackIsRecoveredFromTheProvidersAnswerWithItsRecordAndEvent() {
        OpenedPayment opened = open(Duration.ofMinutes(20));
        provider.answerAll("0", 50_000L, "4088878653");

        ageing.run();

        assertThat(provider.queries()).hasSize(1);
        String requestRef = provider.queries().get(0).requestRef();
        assertThat(requestRef).isNotEqualTo(opened.requestRef());
        Map<String, Object> paid = payment(opened.transactionId());
        assertThat(paid).containsEntry("status", "succeeded").containsEntry("provider_result_code", "0");
        assertThat(paid.get("succeeded_at")).isNotNull();
        assertThat(paid.get("reconciled_at")).isNotNull();
        List<Map<String, Object>> entries = history(opened.transactionId());
        assertThat(entries).hasSize(2);
        assertThat(entries.get(1)).containsEntry("from_status", "pending").containsEntry("to_status", "succeeded")
                .containsEntry("reason", "provider_code:0").containsEntry("evidence_ref", "query:" + requestRef);
        assertThat(row(opened.transactionId()).get("resolved_at")).isNotNull();
        assertThat(drifts(opened.transactionId())).singleElement().satisfies(drift -> assertThat(drift)
                .containsEntry("drift_class", "missed_confirmation").containsEntry("pass", "ageing")
                .containsEntry("our_status", "pending").containsEntry("provider_status", "succeeded")
                .containsEntry("provider_result_code", "0").containsEntry("our_amount_vnd", 50_000L)
                .containsEntry("provider_amount_vnd", 50_000L).containsEntry("resolution", "corrected")
                .containsEntry("needs_person", false));
        assertThat(eventsOf(opened.transactionId())).singleElement().satisfies(published -> {
            assertThat(published.fromStatus()).isEqualTo("pending");
            assertThat(published.toStatus()).isEqualTo("succeeded");
            assertThat(published.evidenceRef()).isEqualTo("query:" + requestRef);
        });
    }

    @Test
    void anExpiredSessionClosesThePaymentAsExpired() {
        OpenedPayment opened = open(Duration.ofMinutes(20));
        provider.answerAll("1005", null, null);

        ageing.run();

        assertThat(payment(opened.transactionId())).containsEntry("status", "expired");
        assertThat(row(opened.transactionId()).get("resolved_at")).isNotNull();
        assertThat(drifts(opened.transactionId())).isEmpty();
    }

    @Test
    void anAnswerSayingTheOrderIsStillInFlightClimbsOneRungAndEachQueryHasItsOwnRequestReference() {
        OpenedPayment opened = open(Duration.ofMinutes(20));
        provider.answerAll("7000", 50_000L, "4088878653");

        ageing.run();

        Map<String, Object> climbed = row(opened.transactionId());
        assertThat(payment(opened.transactionId())).containsEntry("status", "pending");
        assertThat(climbed).containsEntry("attempts", 1).containsEntry("last_outcome", "still_processing");
        Instant swept = ((Timestamp) climbed.get("last_swept_at")).toInstant();
        assertThat(((Timestamp) climbed.get("next_sweep_at")).toInstant()).isEqualTo(swept.plus(Duration.ofMinutes(30)));

        makeDueNow(opened.transactionId());
        ageing.run();

        assertThat(provider.queries()).hasSize(2);
        assertThat(provider.queries().get(0).requestRef()).isNotEqualTo(provider.queries().get(1).requestRef());
        assertThat(provider.queries()).extracting(query -> query.requestRef()).doesNotContain(opened.requestRef());
    }

    @Test
    void aTimeoutSpendsARungAndLeavesThePaymentUnchanged() {
        OpenedPayment opened = open(Duration.ofMinutes(20));
        provider.script(query -> {
            throw new ProviderTransportException(ProviderTransportException.Kind.TIMEOUT, PROVIDER, "query", null);
        });

        ageing.run();

        assertThat(payment(opened.transactionId())).containsEntry("status", "pending");
        assertThat(row(opened.transactionId())).containsEntry("attempts", 1).containsEntry("last_outcome", "query_failed");
    }

    @Test
    void anOpenCircuitDefersTheRowToItsRetryTimeAndEndsThePass() {
        List<OpenedPayment> payments = List.of(open(Duration.ofMinutes(20)), open(Duration.ofMinutes(21)),
                open(Duration.ofMinutes(22)));
        Instant retryAt = Instant.now().plus(Duration.ofMinutes(5)).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        provider.script(query -> {
            throw new ProviderCircuitOpenException(PROVIDER, "query", CircuitState.OPEN, retryAt);
        });

        ageing.run();

        assertThat(provider.queries()).hasSize(1);
        UUID asked = payments.stream().map(OpenedPayment::transactionId)
                .filter(id -> schedule(id).stream().anyMatch(r -> "circuit_open".equals(r.get("last_outcome"))))
                .findFirst().orElseThrow();
        Map<String, Object> deferred = row(asked);
        assertThat(deferred).containsEntry("attempts", 0);
        assertThat(((Timestamp) deferred.get("next_sweep_at")).toInstant()).isEqualTo(retryAt);
        assertThat(payments.stream().filter(p -> !p.transactionId().equals(asked))
                .allMatch(p -> row(p.transactionId()).get("last_outcome") == null)).isTrue();
    }

    @Test
    void aPaymentSettledByACallbackMeanwhileIsResolvedWithoutAsking() {
        OpenedPayment opened = open(Duration.ofMinutes(20));
        provider.answerAll("7000", 50_000L, "4088878653");
        ageing.run();
        provider.queries().clear();
        setStatus(opened.transactionId(), "succeeded");
        makeDueNow(opened.transactionId());

        ageing.run();

        assertThat(provider.queries()).isEmpty();
        assertThat(row(opened.transactionId())).containsEntry("last_outcome", "settled_elsewhere");
        assertThat(row(opened.transactionId()).get("resolved_at")).isNotNull();
    }

    @Test
    void aPendingPaymentPastTheProvidersWindowIsEscalatedAndAStillPendingOneIsLoggedAtError(CapturedOutput output) {
        OpenedPayment opened = open(Duration.ofDays(31).plusMinutes(1));
        jdbc.update("INSERT INTO payment_reconciliation_schedule (id, transaction_id, initiated_at, attempts, "
                + "next_sweep_at, created_at, updated_at) VALUES (gen_random_uuid(), ?, ?, 12, now() - interval '1 minute', "
                + "now(), now())", opened.transactionId(), Timestamp.from(Instant.now().minus(Duration.ofDays(31))));
        double before = count("payment.reconcile.escalation", "reason", "ceiling");

        ageing.run();

        assertThat(provider.queries()).isEmpty();
        assertThat(row(opened.transactionId())).containsEntry("escalation_reason", "ceiling")
                .containsEntry("last_outcome", "escalated");
        assertThat(count("payment.reconcile.escalation", "reason", "ceiling")).isEqualTo(before + 1);
        assertThat(output.getAll().lines().filter(line -> line.contains(opened.transactionId().toString())))
                .anySatisfy(line -> assertThat(line).contains("ERROR").contains("reason=ceiling"));
        ageing.run();
        assertThat(row(opened.transactionId())).containsEntry("attempts", 12);
    }

    @Test
    void aCallbackAndAnAnswerForOnePaymentMoveItOnce() throws Exception {
        OpenedPayment opened = open(Duration.ofMinutes(20));
        Instant paidAt = Instant.now().minusSeconds(30);
        String requestId = UUID.randomUUID().toString();
        requestIds.add(requestId);
        PaymentProviderEvent callback = new PaymentProviderEvent("1", PROVIDER, Kind.INBOUND_PAYMENT_CALLBACK,
                new Reference(opened.orderRef(), UUID.randomUUID().toString(), "4088878653"), "0", "Message",
                50_000L, paidAt, "qr", paidAt);
        inbox.insertAccepted(PROVIDER, requestId, callback, RAW, "118.69.210.244", Instant.now().minusSeconds(3600));
        provider.answerAll("0", 50_000L, "4088878653");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            Future<?> callbackPass = pool.submit(() -> {
                go.await();
                applier.applyPending();
                return null;
            });
            Future<?> answerPass = pool.submit(() -> {
                go.await();
                ageing.run();
                return null;
            });
            go.countDown();
            callbackPass.get();
            answerPass.get();
        } finally {
            pool.shutdownNow();
        }

        assertThat(payment(opened.transactionId())).containsEntry("status", "succeeded");
        assertThat(history(opened.transactionId()).stream().filter(e -> "pending".equals(e.get("from_status"))))
                .hasSize(1);
        assertThat(eventsOf(opened.transactionId())).hasSize(1);
    }

    @Test
    void aLateCallbackAfterTheAnswerSettledThePaymentIsAWarningNotUnappliedMoney() {
        OpenedPayment opened = open(Duration.ofMinutes(20));
        provider.answerAll("0", 50_000L, "4088878653");
        ageing.run();
        double before = count("payment.ledger.unapplied_money");
        String requestId = UUID.randomUUID().toString();
        requestIds.add(requestId);
        Instant older = Instant.now().minus(Duration.ofMinutes(10));
        PaymentProviderEvent late = new PaymentProviderEvent("1", PROVIDER, Kind.INBOUND_PAYMENT_CALLBACK,
                new Reference(opened.orderRef(), UUID.randomUUID().toString(), "4088878653"), "0", "Message",
                50_000L, older, "qr", older);
        inbox.insertAccepted(PROVIDER, requestId, late, RAW, "118.69.210.244", Instant.now().minusSeconds(3600));

        applier.applyPending();

        assertThat(jdbc.queryForObject("SELECT application_reason FROM payment_webhook_event "
                + "WHERE provider_request_id = ?", String.class, requestId)).isEqualTo("stale_event");
        assertThat(count("payment.ledger.unapplied_money")).isEqualTo(before);
        assertThat(history(opened.transactionId())).hasSize(2);
    }

    @Test
    void aFailureWritingTheDriftRecordKeepsNothingOfTheItemAndTheNextRowIsHandled() {
        OpenedPayment failing = open(Duration.ofMinutes(20));
        OpenedPayment behind = open(Duration.ofMinutes(21));
        Map<String, String> codes = new ConcurrentHashMap<>();
        codes.put(failing.orderRef(), "0");
        codes.put(behind.orderRef(), "1006");
        provider.script(query -> new ProviderCallOutcome(ScriptedPaymentProvider.answer(query.orderRef(),
                codes.get(query.orderRef()), 50_000L, "4088878653", Instant.now()), null));
        jdbc.execute("CREATE OR REPLACE FUNCTION it_reconcile_fail() RETURNS trigger LANGUAGE plpgsql AS $f$ BEGIN "
                + "RAISE EXCEPTION 'reconcile it failure'; END $f$");
        jdbc.execute("CREATE TRIGGER it_reconcile_fail BEFORE INSERT ON payment_drift_record "
                + "FOR EACH ROW EXECUTE FUNCTION it_reconcile_fail()");
        double failedBefore = count("payment.reconcile.item", "outcome", "failed");

        ageing.run();

        assertThat(payment(failing.transactionId())).containsEntry("status", "pending")
                .containsEntry("reconciled_at", null);
        assertThat(history(failing.transactionId())).hasSize(1);
        assertThat(row(failing.transactionId())).containsEntry("attempts", 0).containsEntry("last_outcome", null);
        assertThat(publicationsOf(failing.transactionId())).isZero();
        assertThat(eventsOf(failing.transactionId())).isEmpty();
        assertThat(count("payment.reconcile.item", "outcome", "failed")).isEqualTo(failedBefore + 1);
        assertThat(payment(behind.transactionId())).containsEntry("status", "failed");
    }

    @Test
    void anAnswerToAnOrderTheProviderNeverSawIsNeverArrivedAndNeedsNoPerson() {
        OpenedPayment opened = open(Duration.ofMinutes(20));
        provider.answerAll("8888", null, null);

        ageing.run();

        assertThat(payment(opened.transactionId())).containsEntry("status", "pending");
        assertThat(drifts(opened.transactionId())).singleElement().satisfies(drift -> assertThat(drift)
                .containsEntry("drift_class", "never_arrived").containsEntry("needs_person", false)
                .containsEntry("provider_status", "refused:unmapped_code"));
        assertThat(row(opened.transactionId())).containsEntry("attempts", 1).containsEntry("escalated_at", null);
    }

    @Test
    void anIdlePassCountsNoItem() {
        long idleBefore = passes("ageing", "idle");
        double itemsBefore = count("payment.reconcile.item");

        ageing.run();

        assertThat(passes("ageing", "idle")).isEqualTo(idleBefore + 1);
        assertThat(count("payment.reconcile.item")).isEqualTo(itemsBefore);
    }

    @Test
    void aBatchOfDueRowsIsBoundedAndTheRestWaitForTheNextPass() {
        List<OpenedPayment> payments = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            payments.add(open(Duration.ofMinutes(20).plusSeconds(i)));
        }
        provider.answerAll("7000", 50_000L, "4088878653");

        ageing.run();
        long first = provider.queries().size();
        ageing.run();
        long second = provider.queries().size();

        assertThat(first).isEqualTo(50);
        assertThat(second).isEqualTo(70);
    }

    @Test
    void theDailyPassRotatesThroughPaymentsNeverCheckedAndLeavesRowsOfTheLadderAlone() {
        List<OpenedPayment> payments = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            OpenedPayment opened = open(Duration.ofDays(2).plusSeconds(120 - i));
            setStatus(opened.transactionId(), "succeeded");
            payments.add(opened);
        }
        UUID onTheLadder = payments.get(0).transactionId();
        jdbc.update("INSERT INTO payment_reconciliation_schedule (id, transaction_id, initiated_at, attempts, "
                + "next_sweep_at, created_at, updated_at) VALUES (gen_random_uuid(), ?, now(), 0, now(), now(), now())",
                onTheLadder);
        provider.answerAll("0", 50_000L, "4088878653");

        daily.run();

        assertThat(provider.queries()).extracting(query -> query.orderRef())
                .containsExactlyElementsOf(payments.subList(1, 51).stream().map(OpenedPayment::orderRef).toList());

        daily.run();
        daily.run();

        assertThat(provider.queries()).hasSize(119);
        assertThat(provider.queries()).extracting(query -> query.orderRef())
                .doesNotContain(payments.get(0).orderRef());
        long checked = jdbc.queryForObject("SELECT count(*) FROM payment_transaction WHERE user_id = ? "
                + "AND reconciled_at IS NOT NULL", Long.class, user);
        assertThat(checked).isEqualTo(119);
    }

    @Test
    void aPaymentCheckedWithinADayIsNotAskedAboutAgainButIsAfterTwentyFiveHours() {
        OpenedPayment opened = open(Duration.ofDays(3));
        setStatus(opened.transactionId(), "succeeded");
        provider.answerAll("0", 50_000L, "4088878653");

        daily.run();
        int afterFirst = provider.queries().size();
        daily.run();
        int afterSecond = provider.queries().size();
        jdbc.update("UPDATE payment_transaction SET reconciled_at = now() - interval '25 hours' WHERE id = ?",
                opened.transactionId());
        daily.run();

        assertThat(afterFirst).isEqualTo(1);
        assertThat(afterSecond).isEqualTo(1);
        assertThat(provider.queries()).hasSize(2);
    }

    @Test
    void aDailyDisagreementTheRulesRefuseEscalatesTheResolvedRowOnceAndIsNotAskedAgain(CapturedOutput output) {
        OpenedPayment opened = open(Duration.ofDays(3));
        setStatus(opened.transactionId(), "succeeded");
        jdbc.update("INSERT INTO payment_reconciliation_schedule (id, transaction_id, initiated_at, attempts, "
                + "last_swept_at, resolved_at, last_outcome, created_at, updated_at) VALUES (gen_random_uuid(), ?, "
                + "now() - interval '3 days', 2, now(), now(), 'settled_elsewhere', now(), now())",
                opened.transactionId());
        provider.answerAll("1006", 50_000L, "4088878653");

        daily.run();

        assertThat(payment(opened.transactionId())).containsEntry("status", "succeeded");
        assertThat(drifts(opened.transactionId())).singleElement().satisfies(drift -> assertThat(drift)
                .containsEntry("drift_class", "false_positive").containsEntry("pass", "daily")
                .containsEntry("resolution", "refused").containsEntry("needs_person", true));
        Map<String, Object> held = row(opened.transactionId());
        assertThat(held).containsEntry("escalation_reason", "discrepancy").containsEntry("resolved_at", null)
                .containsEntry("next_sweep_at", null);
        assertThat(output.getAll().lines().filter(line -> line.contains(opened.transactionId().toString())))
                .anySatisfy(line -> assertThat(line).contains("ERROR").contains("class=false_positive"));

        jdbc.update("UPDATE payment_transaction SET reconciled_at = now() - interval '25 hours' WHERE id = ?",
                opened.transactionId());
        daily.run();

        assertThat(provider.queries()).hasSize(1);
        assertThat(drifts(opened.transactionId())).hasSize(1);
    }

    @Test
    void aDailyPaymentThatGotAnOpenRowAfterItWasChosenIsSkippedWithNothingApplied() {
        OpenedPayment opened = open(Duration.ofDays(3));
        provider.script(query -> {
            jdbc.update("INSERT INTO payment_reconciliation_schedule (id, transaction_id, initiated_at, attempts, "
                    + "next_sweep_at, created_at, updated_at) VALUES (gen_random_uuid(), ?, now(), 0, now(), now(), now())",
                    opened.transactionId());
            return new ProviderCallOutcome(ScriptedPaymentProvider.answer(query.orderRef(), "0", 50_000L,
                    "4088878653", Instant.now()), null);
        });

        daily.run();

        assertThat(payment(opened.transactionId())).containsEntry("status", "pending");
        assertThat(drifts(opened.transactionId())).isEmpty();
        assertThat(history(opened.transactionId())).hasSize(1);
        assertThat(row(opened.transactionId())).containsEntry("attempts", 0).containsEntry("escalated_at", null);
    }

    @Test
    void aDailyAnswerThatNeedsNoPersonCreatesNoRow() {
        OpenedPayment opened = open(Duration.ofDays(3));
        provider.answerAll("1006", 50_000L, "4088878653");

        daily.run();

        assertThat(payment(opened.transactionId())).containsEntry("status", "failed");
        assertThat(schedule(opened.transactionId())).isEmpty();
        assertThat(provider.queries()).hasSize(1);
    }

    /**
     * Every ask carries a fresh request reference, so no pass writes one evidence reference twice; a trigger
     * that has a twin of the record already written when the item inserts its own stands in for the retry.
     */
    @Test
    void aDriftRecordAlreadyWrittenForTheSameEvidenceLeavesOneRecordAndTheItemStillCommits() {
        OpenedPayment opened = open(Duration.ofMinutes(20));
        provider.answerAll("0", 50_000L, "4088878653");
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION it_reconcile_twin() RETURNS trigger LANGUAGE plpgsql AS $f$ BEGIN
                INSERT INTO payment_drift_record (id, transaction_id, drift_class, pass, provider, our_status,
                    provider_status, provider_result_code, our_amount_vnd, provider_amount_vnd, evidence_ref,
                    resolution, needs_person, detected_at)
                VALUES (gen_random_uuid(), NEW.transaction_id, NEW.drift_class, NEW.pass, NEW.provider,
                    NEW.our_status, NEW.provider_status, NEW.provider_result_code, NEW.our_amount_vnd,
                    NEW.provider_amount_vnd, NEW.evidence_ref, NEW.resolution, NEW.needs_person, NEW.detected_at);
                RETURN NEW; END $f$
                """);
        jdbc.execute("CREATE TRIGGER it_reconcile_twin BEFORE INSERT ON payment_drift_record FOR EACH ROW "
                + "WHEN (pg_trigger_depth() = 0) EXECUTE FUNCTION it_reconcile_twin()");
        double failedBefore = count("payment.reconcile.item", "outcome", "failed");

        ageing.run();

        String evidence = "query:" + provider.queries().get(0).requestRef();
        assertThat(drifts(opened.transactionId())).singleElement()
                .satisfies(drift -> assertThat(drift).containsEntry("evidence_ref", evidence));
        assertThat(payment(opened.transactionId())).containsEntry("status", "succeeded");
        assertThat(row(opened.transactionId()).get("resolved_at")).isNotNull();
        assertThat(count("payment.reconcile.item", "outcome", "failed")).isEqualTo(failedBefore);
    }

    @Test
    void aDispositionWriteOnARowAnotherWriterAlreadyClosedChangesNothing() {
        OpenedPayment advancing = open(Duration.ofMinutes(20));
        OpenedPayment resolving = open(Duration.ofMinutes(21));
        OpenedPayment escalating = open(Duration.ofMinutes(22));
        Map<String, UUID> ids = Map.of(advancing.orderRef(), advancing.transactionId(),
                resolving.orderRef(), resolving.transactionId(), escalating.orderRef(), escalating.transactionId());
        Map<String, String> codes = Map.of(advancing.orderRef(), "7000", resolving.orderRef(), "1005",
                escalating.orderRef(), "0");
        Map<UUID, Map<String, Object>> closed = new ConcurrentHashMap<>();
        provider.script(query -> {
            UUID id = ids.get(query.orderRef());
            if (query.orderRef().equals(resolving.orderRef())) {
                jdbc.update("UPDATE payment_reconciliation_schedule SET escalated_at = now(), "
                        + "escalation_reason = 'ceiling', next_sweep_at = NULL, last_outcome = 'escalated' "
                        + "WHERE transaction_id = ?", id);
            } else {
                jdbc.update("UPDATE payment_reconciliation_schedule SET resolved_at = now(), next_sweep_at = NULL, "
                        + "last_outcome = 'settled_elsewhere' WHERE transaction_id = ?", id);
            }
            closed.put(id, row(id));
            long amount = query.orderRef().equals(escalating.orderRef()) ? 40_000L : 50_000L;
            return new ProviderCallOutcome(ScriptedPaymentProvider.answer(query.orderRef(),
                    codes.get(query.orderRef()), amount, "4088878653", Instant.now()), null);
        });
        double failedBefore = count("payment.reconcile.item", "outcome", "failed");

        ageing.run();

        assertThat(provider.queries()).hasSize(3);
        assertThat(count("payment.reconcile.item", "outcome", "failed")).isEqualTo(failedBefore);
        assertThat(payment(advancing.transactionId())).containsEntry("status", "pending");
        assertThat(payment(resolving.transactionId())).containsEntry("status", "expired");
        assertThat(drifts(escalating.transactionId())).singleElement().satisfies(drift -> assertThat(drift)
                .containsEntry("drift_class", "amount_mismatch").containsEntry("needs_person", true));
        for (OpenedPayment item : List.of(advancing, resolving, escalating)) {
            assertThat(row(item.transactionId())).isEqualTo(closed.get(item.transactionId()));
        }
        assertThat(row(advancing.transactionId())).containsEntry("attempts", 0).containsEntry("escalated_at", null);
        assertThat(row(resolving.transactionId())).containsEntry("attempts", 0).containsEntry("resolved_at", null)
                .containsEntry("escalation_reason", "ceiling");
        assertThat(row(escalating.transactionId())).containsEntry("attempts", 0).containsEntry("escalated_at", null)
                .containsEntry("escalation_reason", null).containsEntry("last_outcome", "settled_elsewhere");
    }

    @Test
    void anOlderDailyAnswerAfterExpiryIsSupersededAndEscalatesTheRowForAPerson(CapturedOutput output) {
        OpenedPayment opened = open(Duration.ofDays(3));
        Instant recorded = Instant.now().minus(Duration.ofHours(2));
        jdbc.update("UPDATE payment_transaction SET status = 'expired', provider_status_at = ? WHERE id = ?",
                Timestamp.from(recorded), opened.transactionId());
        Instant older = recorded.minus(Duration.ofMinutes(10));
        provider.script(query -> new ProviderCallOutcome(ScriptedPaymentProvider.answer(query.orderRef(), "0",
                50_000L, "4088878653", older), null));

        daily.run();

        assertThat(payment(opened.transactionId())).containsEntry("status", "expired");
        assertThat(history(opened.transactionId())).hasSize(1);
        assertThat(drifts(opened.transactionId())).singleElement().satisfies(drift -> assertThat(drift)
                .containsEntry("drift_class", "missed_confirmation").containsEntry("pass", "daily")
                .containsEntry("our_status", "expired").containsEntry("provider_status", "succeeded")
                .containsEntry("resolution", "superseded").containsEntry("needs_person", true));
        assertThat(row(opened.transactionId())).containsEntry("escalation_reason", "discrepancy")
                .containsEntry("last_outcome", "discrepancy").containsEntry("resolved_at", null)
                .containsEntry("next_sweep_at", null);
        assertThat(row(opened.transactionId()).get("escalated_at")).isNotNull();
        assertThat(output.getAll().lines().filter(line -> line.contains(opened.transactionId().toString())))
                .anySatisfy(line -> assertThat(line).contains("ERROR").contains("class=missed_confirmation")
                        .contains("resolution=superseded"));
    }

    @Test
    void aDailyDisagreementForARowThatBecameOpenAfterTheCheckEscalatesThatRowOnce() {
        OpenedPayment opened = open(Duration.ofDays(3));
        setStatus(opened.transactionId(), "succeeded");
        provider.answerAll("1006", 50_000L, "4088878653");
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION it_reconcile_enrol() RETURNS trigger LANGUAGE plpgsql AS $f$ BEGIN
                INSERT INTO payment_reconciliation_schedule (id, transaction_id, initiated_at, attempts,
                    next_sweep_at, created_at, updated_at)
                VALUES (gen_random_uuid(), NEW.transaction_id, now(), 3, now(), now(), now());
                RETURN NEW; END $f$
                """);
        jdbc.execute("CREATE TRIGGER it_reconcile_enrol BEFORE INSERT ON payment_drift_record FOR EACH ROW "
                + "EXECUTE FUNCTION it_reconcile_enrol()");

        daily.run();

        assertThat(drifts(opened.transactionId())).singleElement().satisfies(drift -> assertThat(drift)
                .containsEntry("drift_class", "false_positive").containsEntry("needs_person", true));
        Map<String, Object> held = row(opened.transactionId());
        assertThat(held).containsEntry("escalation_reason", "discrepancy").containsEntry("last_outcome", "discrepancy")
                .containsEntry("attempts", 4).containsEntry("resolved_at", null)
                .containsEntry("next_sweep_at", null);
        assertThat(held.get("escalated_at")).isNotNull();
    }
}
