package com.flowcortex.app.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import java.sql.Connection;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The plan grant inside the settlement transaction, against the real schema. One callback is applied per pass so
 * that two threads each decide exactly one.
 */
@SpringBootTest(properties = "flowcortex.billing.ledger.apply.batch-size=1")
class EntitlementGrantIT extends AbstractPostgresBootTest {

    private static final String PROVIDER = "momo";
    private static final String MERCHANT = "MOMOBKUN20180529";
    private static final byte[] RAW = {'{', '}'};
    private static final Instant PAID_AT = Instant.parse("2026-11-20T10:00:05Z");
    // Older than anything another class could leave behind, so a pass takes this class's rows first.
    private static final Instant RECEIVED = Instant.now().minus(1, ChronoUnit.HOURS);
    private static final String TELEMETRY_LOGGER = "com.flowcortex.billing.entitlement.EntitlementTelemetry";

    private final UUID user = UUID.randomUUID();
    private final List<String> requestIds = new ArrayList<>();
    private final List<UUID> transactions = new ArrayList<>();
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private int received;

    @Autowired
    private PaymentLedger ledger;

    @Autowired
    private PaymentWebhookEventRepository inbox;

    @Autowired
    private LedgerApplier applier;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private MeterRegistry meters;

    @BeforeEach
    void captureLog() {
        logged.start();
        ((Logger) LoggerFactory.getLogger(TELEMETRY_LOGGER)).addAppender(logged);
    }

    @AfterEach
    void clean() {
        ((Logger) LoggerFactory.getLogger(TELEMETRY_LOGGER)).detachAppender(logged);
        logged.stop();
        jdbc.execute("DROP TRIGGER IF EXISTS it_grant_fail ON member_subscription");
        jdbc.execute("DROP FUNCTION IF EXISTS it_grant_fail()");
        jdbc.update("DELETE FROM member_subscription_event WHERE user_id = ?", user);
        jdbc.update("DELETE FROM member_subscription WHERE user_id = ?", user);
        for (String requestId : requestIds) {
            jdbc.update("DELETE FROM payment_webhook_event WHERE provider_request_id = ?", requestId);
        }
        jdbc.update("DELETE FROM payment_transaction_event WHERE transaction_id IN "
                + "(SELECT id FROM payment_transaction WHERE user_id = ?)", user);
        jdbc.update("DELETE FROM payment_transaction WHERE user_id = ?", user);
    }

    private OpenedPayment open(String kind, String itemRef) {
        OpenedPayment opened = ledger.open(new OpenPayment(user, kind, itemRef, "Purchase", 199_000L, PROVIDER, MERCHANT,
                Optional.of(PaymentInstrument.WALLET)));
        transactions.add(opened.transactionId());
        return opened;
    }

    private UUID accept(OpenedPayment opened) {
        String requestId = UUID.randomUUID().toString();
        requestIds.add(requestId);
        Instant receivedAt = RECEIVED.plusMillis(received++);
        PaymentProviderEvent event = new PaymentProviderEvent("1", PROVIDER, Kind.INBOUND_PAYMENT_CALLBACK,
                new Reference(opened.orderRef(), UUID.randomUUID().toString(), "4088878653"), "0", "Message", 199_000L,
                PAID_AT, "qr", PAID_AT);
        inbox.insertAccepted(PROVIDER, requestId, event, RAW, "118.69.210.244", receivedAt);
        return jdbc.queryForObject("SELECT id FROM payment_webhook_event WHERE provider = ? AND provider_request_id = ?",
                UUID.class, PROVIDER, requestId);
    }

    /** Settles a payment through the applier, one callback a pass, until its callback is decided. */
    private UUID settle(OpenedPayment opened) {
        UUID callback = accept(opened);
        for (int pass = 0; pass < 30 && callback(callback).get("applied_at") == null; pass++) {
            applier.applyPending();
        }
        return callback;
    }

    private OpenedPayment settledPlan(String itemRef) {
        OpenedPayment opened = open("plan_purchase", itemRef);
        settle(opened);
        return opened;
    }

    private Map<String, Object> callback(UUID id) {
        return jdbc.queryForMap("SELECT applied_at, application_outcome, apply_attempts FROM payment_webhook_event "
                + "WHERE id = ?", id);
    }

    private Map<String, Object> payment(UUID id) {
        return jdbc.queryForMap("SELECT status, succeeded_at, period_starts_at, period_renews_at "
                + "FROM payment_transaction WHERE id = ?", id);
    }

    private Instant instant(Object timestamp) {
        return ((Timestamp) timestamp).toInstant();
    }

    private Optional<Map<String, Object>> holding() {
        return jdbc.queryForList("SELECT * FROM member_subscription WHERE user_id = ?", user).stream().findFirst();
    }

    private List<Map<String, Object>> events() {
        return jdbc.queryForList("SELECT transaction_id, from_tier_id, to_tier_id, from_status, to_status, reason "
                + "FROM member_subscription_event WHERE user_id = ? ORDER BY occurred_at, id", user);
    }

    private double count(String name, String... tags) {
        return meters.find(name).tags(tags).counters().stream().mapToDouble(Counter::count).sum();
    }

    private long waitingForMemberLock() {
        return jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted "
                + "AND ((classid::bigint << 32) | objid::bigint) = hashtextextended('member-plan:' || ?, 0)",
                Long.class, user.toString());
    }

    @Test
    void aPlanPurchaseSettlesWithItsPeriodAndOpensTheHolding() {
        double before = count("billing.entitlement.decision", "outcome", "granted", "reason", "none");

        OpenedPayment basic = settledPlan("basic:monthly");

        Map<String, Object> row = payment(basic.transactionId());
        assertThat(row).containsEntry("status", "succeeded");
        Instant settled = instant(row.get("succeeded_at"));
        assertThat(instant(row.get("period_starts_at"))).isEqualTo(settled);
        assertThat(instant(row.get("period_renews_at"))).isEqualTo(
                settled.atZone(ZoneOffset.UTC).plusMonths(1).toInstant());
        Map<String, Object> held = holding().orElseThrow();
        assertThat(held).containsEntry("tier_id", "basic")
                .containsEntry("billing_cycle", "monthly")
                .containsEntry("status", "active")
                .containsEntry("billing_mode", "manual")
                .containsEntry("granted_from_transaction_id", basic.transactionId())
                .containsEntry("pending_transaction_id", null);
        assertThat(instant(held.get("started_at"))).isEqualTo(settled);
        assertThat(instant(held.get("renews_at"))).isEqualTo(instant(row.get("period_renews_at")));
        assertThat(events()).singleElement().satisfies(event -> assertThat(event)
                .containsEntry("transaction_id", basic.transactionId())
                .containsEntry("from_tier_id", null)
                .containsEntry("from_status", null)
                .containsEntry("to_tier_id", "basic")
                .containsEntry("to_status", "active")
                .containsEntry("reason", "settled_payment:basic"));
        assertThat(count("billing.entitlement.decision", "outcome", "granted", "reason", "none")).isEqualTo(before + 1);
    }

    @Test
    void aTopUpSettlesWithNoPeriodAndNoHolding() {
        OpenedPayment topup = open("topup", "pkg-50k");
        settle(topup);

        assertThat(payment(topup.transactionId())).containsEntry("status", "succeeded")
                .containsEntry("period_starts_at", null)
                .containsEntry("period_renews_at", null);
        assertThat(holding()).isEmpty();
        assertThat(events()).isEmpty();
    }

    @Test
    void aSecondPurchaseAheadKeepsTheHeldPlanAndIsRecordedAsNext() {
        OpenedPayment basic = settledPlan("basic:monthly");
        double before = count("billing.entitlement.decision", "outcome", "pending_recorded", "reason", "none");

        OpenedPayment advanced = settledPlan("advanced:monthly");

        Map<String, Object> held = holding().orElseThrow();
        assertThat(held).containsEntry("tier_id", "basic")
                .containsEntry("granted_from_transaction_id", basic.transactionId())
                .containsEntry("pending_tier_id", "advanced")
                .containsEntry("pending_billing_cycle", "monthly")
                .containsEntry("pending_transaction_id", advanced.transactionId());
        Map<String, Object> next = payment(advanced.transactionId());
        assertThat(instant(next.get("period_starts_at"))).isEqualTo(instant(held.get("renews_at")));
        assertThat(events()).extracting(event -> event.get("reason"))
                .containsExactly("settled_payment:basic", "renewal_plan_selected:advanced");
        assertThat(count("billing.entitlement.decision", "outcome", "pending_recorded", "reason", "none"))
                .isEqualTo(before + 1);
    }

    @Test
    void aThirdPurchaseAheadIsRefusedStoresNoPeriodAndIsLoggedWithoutTheMember() {
        settledPlan("basic:monthly");
        OpenedPayment advanced = settledPlan("advanced:monthly");
        double before = count("billing.entitlement.decision", "outcome", "refused", "reason", "pending_plan_occupied");

        OpenedPayment third = settledPlan("basic:monthly");

        assertThat(payment(third.transactionId())).containsEntry("status", "succeeded")
                .containsEntry("period_starts_at", null)
                .containsEntry("period_renews_at", null);
        assertThat(holding().orElseThrow()).containsEntry("pending_transaction_id", advanced.transactionId());
        assertThat(events()).last().satisfies(event -> assertThat(event)
                .containsEntry("transaction_id", third.transactionId())
                .containsEntry("from_tier_id", null)
                .containsEntry("to_tier_id", null)
                .containsEntry("reason", "refused:pending_plan_occupied"));
        assertThat(count("billing.entitlement.decision", "outcome", "refused", "reason", "pending_plan_occupied"))
                .isEqualTo(before + 1);
        assertThat(logged.list).filteredOn(event -> event.getLevel() == Level.ERROR).singleElement()
                .satisfies(event -> assertThat(event.getFormattedMessage())
                        .contains(third.transactionId().toString())
                        .contains("pending_plan_occupied")
                        .doesNotContain(user.toString()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"gold:monthly", "free:monthly", "basic:yearly"})
    void aPurchaseThatNamesNoSoldPlanIsRefusedUnrecordedAndStoresNoPeriod(String itemRef) {
        double before = count("billing.entitlement.decision", "outcome", "refused", "reason", "plan_unrecorded");

        OpenedPayment purchase = settledPlan(itemRef);

        assertThat(payment(purchase.transactionId())).containsEntry("status", "succeeded")
                .containsEntry("period_starts_at", null);
        assertThat(holding()).isEmpty();
        assertThat(events()).singleElement().satisfies(event -> assertThat(event)
                .containsEntry("reason", "refused:plan_unrecorded"));
        assertThat(count("billing.entitlement.decision", "outcome", "refused", "reason", "plan_unrecorded"))
                .isEqualTo(before + 1);
        assertThat(logged.list).filteredOn(event -> event.getLevel() == Level.ERROR).singleElement()
                .satisfies(event -> assertThat(event.getFormattedMessage())
                        .contains(purchase.transactionId().toString())
                        .contains("plan_unrecorded"));
    }

    @Test
    void aRefusedPurchaseDoesNotPushTheNextOneOutOfTheScheduleAfterActivation() {
        settledPlan("basic:monthly");
        OpenedPayment advanced = settledPlan("advanced:monthly");
        OpenedPayment refused = settledPlan("basic:monthly");
        pastBoundary(advanced);

        OpenedPayment fourth = settledPlan("basic:monthly");

        assertThat(payment(refused.transactionId())).containsEntry("period_starts_at", null);
        Map<String, Object> period = payment(fourth.transactionId());
        assertThat(instant(period.get("period_starts_at"))).isEqualTo(instant(payment(advanced.transactionId())
                .get("period_renews_at")));
        assertThat(holding().orElseThrow()).containsEntry("tier_id", "advanced")
                .containsEntry("pending_transaction_id", fourth.transactionId());
    }

    @Test
    void payingAgainAfterTheBoundaryBeforeTheJobRanActivatesThePendingPlanFirst() {
        settledPlan("basic:monthly");
        OpenedPayment advanced = settledPlan("advanced:monthly");
        pastBoundary(advanced);
        double before = count("billing.entitlement.activation", "trigger", "grant");

        OpenedPayment again = settledPlan("basic:monthly");

        Map<String, Object> held = holding().orElseThrow();
        assertThat(held).containsEntry("tier_id", "advanced")
                .containsEntry("granted_from_transaction_id", advanced.transactionId())
                .containsEntry("pending_tier_id", "basic")
                .containsEntry("pending_transaction_id", again.transactionId());
        assertThat(instant(payment(again.transactionId()).get("period_starts_at")))
                .isEqualTo(instant(held.get("renews_at")));
        assertThat(events()).extracting(event -> event.get("reason")).contains("renewal_plan_activated:advanced",
                "renewal_plan_selected:basic");
        assertThat(count("billing.entitlement.activation", "trigger", "grant")).isEqualTo(before + 1);
    }

    /** Moves the holding and the next-cycle payment's period so that the next cycle began ten days ago. */
    private void pastBoundary(OpenedPayment next) {
        Instant now = Instant.now();
        jdbc.update("UPDATE payment_transaction SET period_starts_at = ?, period_renews_at = ? WHERE id = ?",
                Timestamp.from(now.minus(10, ChronoUnit.DAYS)), Timestamp.from(now.plus(20, ChronoUnit.DAYS)),
                next.transactionId());
        jdbc.update("UPDATE member_subscription SET started_at = ?, renews_at = ? WHERE user_id = ?",
                Timestamp.from(now.minus(40, ChronoUnit.DAYS)), Timestamp.from(now.minus(10, ChronoUnit.DAYS)), user);
    }

    @Test
    void twoFirstPurchasesAppliedAtOnceOpenOneHoldingAndRecordTheOtherNext() throws Exception {
        OpenedPayment first = open("plan_purchase", "basic:monthly");
        OpenedPayment second = open("plan_purchase", "basic:monthly");
        UUID firstCallback = accept(first);
        UUID secondCallback = accept(second);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (Statement lock = holder.createStatement()) {
                lock.execute("SELECT pg_advisory_xact_lock(hashtextextended('member-plan:" + user + "', 0))");
            }
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> passes = List.of(pool.submit(() -> pass(go)), pool.submit(() -> pass(go)));
            go.countDown();
            awaitWaiters(2);
            holder.rollback();
            for (Future<?> pass : passes) {
                pass.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(callback(firstCallback)).containsEntry("application_outcome", "applied");
        assertThat(callback(secondCallback)).containsEntry("application_outcome", "applied");
        Map<String, Object> held = holding().orElseThrow();
        UUID opened = (UUID) held.get("granted_from_transaction_id");
        UUID next = (UUID) held.get("pending_transaction_id");
        assertThat(List.of(opened, next)).containsExactlyInAnyOrder(first.transactionId(), second.transactionId());
        assertThat(instant(payment(next).get("period_starts_at"))).isEqualTo(instant(payment(opened).get("period_renews_at")));
        assertThat(instant(held.get("renews_at"))).isEqualTo(instant(payment(opened).get("period_renews_at")));
    }

    private Object pass(CountDownLatch go) throws InterruptedException {
        go.await();
        applier.applyPending();
        return null;
    }

    private void awaitWaiters(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (waitingForMemberLock() < expected) {
            assertThat(System.nanoTime()).as("both passes wait for the member lock").isLessThan(deadline);
            Thread.sleep(50);
        }
    }

    @Test
    void aFailureWhileWritingTheHoldingRollsTheMoneyMoveBackAndCountsOneAttempt() {
        jdbc.execute("CREATE OR REPLACE FUNCTION it_grant_fail() RETURNS trigger LANGUAGE plpgsql AS $f$ BEGIN "
                + "RAISE EXCEPTION 'grant it failure'; END $f$");
        jdbc.execute("CREATE TRIGGER it_grant_fail BEFORE INSERT ON member_subscription "
                + "FOR EACH ROW EXECUTE FUNCTION it_grant_fail()");
        OpenedPayment basic = open("plan_purchase", "basic:monthly");
        UUID callback = accept(basic);

        applier.applyPending();

        assertThat(payment(basic.transactionId())).containsEntry("status", "pending")
                .containsEntry("period_starts_at", null)
                .containsEntry("succeeded_at", null);
        assertThat(holding()).isEmpty();
        assertThat(events()).isEmpty();
        assertThat(callback(callback)).containsEntry("apply_attempts", 1).containsEntry("applied_at", null);
    }

    @Test
    void theDatabaseRefusesAStartWithoutARenewalAndARenewalNotAfterTheStart() {
        OpenedPayment basic = settledPlan("basic:monthly");
        UUID id = basic.transactionId();

        assertThatThrownBy(() -> jdbc.update("UPDATE payment_transaction SET period_renews_at = NULL WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("payment_transaction_period_pair_check");
        assertThatThrownBy(() -> jdbc.update("UPDATE payment_transaction SET period_renews_at = period_starts_at "
                + "WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("payment_transaction_period_order_check");
    }

    @Test
    void theDatabaseRefusesAPeriodOnATopUpAndOnAPaymentWithNoPaidTime() {
        OpenedPayment topup = open("topup", "pkg-50k");
        settle(topup);
        OpenedPayment pending = open("plan_purchase", "basic:monthly");

        assertThatThrownBy(() -> jdbc.update("UPDATE payment_transaction SET period_starts_at = now(), "
                + "period_renews_at = now() + interval '1 day' WHERE id = ?", topup.transactionId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("payment_transaction_period_kind_check");
        assertThatThrownBy(() -> jdbc.update("UPDATE payment_transaction SET period_starts_at = now(), "
                + "period_renews_at = now() + interval '1 day' WHERE id = ?", pending.transactionId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("payment_transaction_period_kind_check");
    }
}
