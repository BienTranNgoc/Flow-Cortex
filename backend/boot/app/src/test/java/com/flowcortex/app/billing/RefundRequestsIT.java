package com.flowcortex.app.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.billing.refund.RefundNotEligibleException;
import com.flowcortex.billing.refund.RefundRequestNotCancellableException;
import com.flowcortex.billing.refund.RefundRequests;
import com.flowcortex.billing.refund.RefundResponses.ChangeOfMindResponse;
import com.flowcortex.billing.refund.RefundResponses.RefundEligibilityResponse;
import com.flowcortex.billing.refund.RefundResponses.RefundRequestView;
import com.flowcortex.billing.refund.RefundSettings;
import com.flowcortex.billing.refund.RefundTransactionNotFoundException;
import com.flowcortex.settings.api.Settings;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class RefundRequestsIT extends AbstractPostgresBootTest {

    private static final Instant PAID = Instant.parse("2026-11-20T10:00:00Z");
    private static final Instant NOW = PAID.plus(Duration.ofHours(30));
    private static final String DEFAULT_REASON = "Khách sử dụng quyền đổi ý trong những ngày đầu sau khi thanh toán.";

    private final UUID memberA = UUID.randomUUID();
    private final UUID memberB = UUID.randomUUID();
    private final UUID operator = UUID.randomUUID();

    @Autowired
    private RefundRequests refunds;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Settings settings;

    @Autowired
    private MeterRegistry meters;

    private final ListAppender<ILoggingEvent> logLines = new ListAppender<>();
    private Logger telemetryLogger;

    @BeforeEach
    void captureLog() {
        telemetryLogger = (Logger) LoggerFactory.getLogger("com.flowcortex.billing.refund.RefundTelemetry");
        logLines.start();
        telemetryLogger.addAppender(logLines);
    }

    @AfterEach
    void clean() {
        telemetryLogger.detachAppender(logLines);
        logLines.stop();
        for (UUID member : List.of(memberA, memberB)) {
            jdbc.update("DELETE FROM refund_request WHERE user_id = ?", member);
            jdbc.update("DELETE FROM payment_transaction WHERE user_id = ?", member);
        }
        jdbc.update("DELETE FROM app_setting WHERE key LIKE 'payment.change_of_mind.%' OR key LIKE 'payment.customer_refund.%'");
        jdbc.update("DELETE FROM app_setting_change WHERE key LIKE 'payment.change_of_mind.%'"
                + " OR key LIKE 'payment.customer_refund.%'");
    }

    private UUID payment(UUID member, String kind, String itemRef, String status, Instant paidAt) {
        UUID id = UUID.randomUUID();
        Timestamp opened = Timestamp.from(paidAt == null ? PAID : paidAt);
        jdbc.update("""
                INSERT INTO payment_transaction (id, user_id, kind, item_ref, description, amount_vnd, provider,
                    merchant_code, order_ref, request_ref, status, created_at, updated_at, succeeded_at)
                VALUES (?, ?, ?, ?, 'Payment', 199000, 'momo', 'MOMOBKUN20180529', ?, ?, ?, ?, ?, ?)
                """, id, member, kind, itemRef, "fc_" + id.toString().replace("-", ""), UUID.randomUUID(), status,
                opened, opened, paidAt == null ? null : Timestamp.from(paidAt));
        return id;
    }

    private UUID topup(UUID member) {
        return payment(member, "topup", "pkg-50k", "succeeded", PAID);
    }

    private long rows(UUID member) {
        return jdbc.queryForObject("SELECT count(*) FROM refund_request WHERE user_id = ?", Long.class, member);
    }

    /** A counted turn held by the member on a payment of its own, created at the given instant. */
    private void spendTurn(UUID member, Instant at) {
        UUID id = topup(member);
        jdbc.update("""
                INSERT INTO refund_request (id, user_id, transaction_id, reason, status, created_at, updated_at)
                VALUES (?, ?, ?, 'Spent turn', 'pending', ?, ?)
                """, UUID.randomUUID(), member, id, Timestamp.from(at), Timestamp.from(at));
    }

    private void decide(UUID requestId, String status, String note, Instant at) {
        jdbc.update("UPDATE refund_request SET status = ?, decided_at = ?, decision_note = ?, updated_at = ? WHERE id = ?",
                status, Timestamp.from(at), note, Timestamp.from(at), requestId);
    }

    private double refusalCount(String refusal) {
        var counter = meters.find("billing.refund.request").tag("outcome", "refused").tag("refusal", refusal).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void aPermittedRequestStoresAPendingRowWithTheTrimmedReasonAndACode() {
        UUID id = topup(memberA);

        RefundRequestView view = refunds.request(memberA, id, "  Tôi mua nhầm gói tháng.  ", NOW);

        assertThat(view.status()).isEqualTo("pending");
        assertThat(view.reason()).isEqualTo("Tôi mua nhầm gói tháng.");
        assertThat(view.code()).matches("HT-\\d{6,}");
        assertThat(view.requestedAt()).isEqualTo(NOW);
        assertThat(view.decidedAt()).isNull();
        assertThat(rows(memberA)).isOne();
    }

    @Test
    void twoRequestsOneAfterTheOtherGetConsecutiveCodes() {
        RefundRequestView first = refunds.request(memberA, topup(memberA), "Lý do thứ nhất của tôi", NOW);
        RefundRequestView second = refunds.request(memberA, topup(memberA), "Lý do thứ hai của tôi", NOW.plusSeconds(1));

        assertThat(number(second.code())).isEqualTo(number(first.code()) + 1);
    }

    @Test
    void anAbsentOrBlankReasonStoresTheDefaultSentence() {
        RefundRequestView absent = refunds.request(memberA, topup(memberA), null, NOW);
        RefundRequestView blank = refunds.request(memberA, topup(memberA), "     ", NOW.plusSeconds(1));

        assertThat(absent.reason()).isEqualTo(DEFAULT_REASON);
        assertThat(blank.reason()).isEqualTo(DEFAULT_REASON);
    }

    @Test
    void askingAgainWhilePendingAnswersTheSameRequestEvenAfterTheWindow() {
        UUID id = topup(memberA);
        RefundRequestView first = refunds.request(memberA, id, "Lý do của tôi ở đây", NOW);

        RefundRequestView again = refunds.request(memberA, id, "Lý do khác hẳn", NOW.plus(Duration.ofDays(10)));

        assertThat(again.id()).isEqualTo(first.id());
        assertThat(again.reason()).isEqualTo("Lý do của tôi ở đây");
        assertThat(rows(memberA)).isOne();
    }

    @Test
    void aRequestAfterTheWindowIsRefusedWithNothingWrittenAndCounted() {
        UUID id = topup(memberA);
        double before = refusalCount("window_elapsed");

        assertThatThrownBy(() -> refunds.request(memberA, id, "Lý do của tôi ở đây", PAID.plus(Duration.ofDays(4))))
                .isInstanceOfSatisfying(RefundNotEligibleException.class, e -> {
                    assertThat(e.right().refusal()).isEqualTo("window_elapsed");
                    assertThat(e.right().deadlineAt()).isEqualTo(PAID.plus(Duration.ofDays(3)));
                });

        assertThat(rows(memberA)).isZero();
        assertThat(refusalCount("window_elapsed")).isEqualTo(before + 1);
    }

    @Test
    void twoCountedTurnsRefuseTheNextRequestUntilTheLimitIsRaised() {
        spendTurn(memberA, NOW.minus(Duration.ofDays(40)));
        spendTurn(memberA, NOW.minus(Duration.ofDays(10)));
        UUID id = topup(memberA);

        assertThatThrownBy(() -> refunds.request(memberA, id, "Lý do của tôi ở đây", NOW))
                .isInstanceOfSatisfying(RefundNotEligibleException.class, e -> {
                    assertThat(e.right().refusal()).isEqualTo("turn_limit_reached");
                    assertThat(e.turns().limit()).isEqualTo(2);
                    assertThat(e.turns().windowMonths()).isEqualTo(6);
                    assertThat(e.turns().nextTurnAt())
                            .isEqualTo(NOW.minus(Duration.ofDays(40)).atOffset(ZoneOffset.UTC).plusMonths(6)
                                    .toInstant());
                });
        assertThat(rows(memberA)).isEqualTo(2);

        settings.set(RefundSettings.LIMIT_USES, 3, operator);

        assertThat(refunds.request(memberA, id, "Lý do của tôi ở đây", NOW).status()).isEqualTo("pending");
    }

    @Test
    void twoThreadsOnOnePaymentStoreOneRowAndBothGetItsId() throws Exception {
        UUID id = topup(memberA);

        List<RefundRequestView> answers = inParallel(
                () -> refunds.request(memberA, id, "Lý do của tôi ở đây", NOW),
                () -> refunds.request(memberA, id, "Lý do của tôi ở đây", NOW));

        assertThat(answers).extracting(RefundRequestView::id).containsOnly(answers.getFirst().id());
        assertThat(rows(memberA)).isOne();
    }

    @Test
    void twoThreadsOnOnePaymentWithOneTurnLeftStillBothGetItsId() throws Exception {
        spendTurn(memberA, NOW.minus(Duration.ofDays(5)));
        UUID id = topup(memberA);

        List<RefundRequestView> answers = inParallel(
                () -> refunds.request(memberA, id, "Lý do của tôi ở đây", NOW),
                () -> refunds.request(memberA, id, "Lý do của tôi ở đây", NOW));

        assertThat(answers).extracting(RefundRequestView::id).containsOnly(answers.getFirst().id());
        assertThat(rows(memberA)).isEqualTo(2);
    }

    @Test
    void twoThreadsOnTwoPaymentsWithOneTurnLeftStoreOneAndRefuseTheOther() throws Exception {
        spendTurn(memberA, NOW.minus(Duration.ofDays(5)));
        UUID first = topup(memberA);
        UUID second = topup(memberA);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<String> one = pool.submit(() -> outcome(start, () -> refunds.request(memberA, first, "Lý do một", NOW)));
            Future<String> two = pool.submit(() -> outcome(start, () -> refunds.request(memberA, second, "Lý do hai", NOW)));
            start.countDown();

            assertThat(List.of(one.get(), two.get())).containsExactlyInAnyOrder("stored", "turn_limit_reached");
        } finally {
            pool.shutdownNow();
        }
        assertThat(rows(memberA)).isEqualTo(2);
    }

    @Test
    void cancellingMovesThePendingRequestToCancelledAndAllowsANewOneWithANewCode() {
        UUID id = topup(memberA);
        RefundRequestView first = refunds.request(memberA, id, "Lý do của tôi ở đây", NOW);
        assertThat(refunds.eligibility(memberA, id, NOW).cancellable()).isTrue();

        RefundRequestView cancelled = refunds.cancel(memberA, id, NOW.plusSeconds(60));

        assertThat(cancelled.id()).isEqualTo(first.id());
        assertThat(cancelled.status()).isEqualTo("cancelled");
        assertThat(cancelled.decidedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(jdbc.queryForObject("SELECT decided_by FROM refund_request WHERE id = ?", UUID.class, first.id()))
                .isEqualTo(memberA);
        assertThat(refunds.eligibility(memberA, id, NOW.plusSeconds(61)).cancellable()).isFalse();

        RefundRequestView second = refunds.request(memberA, id, "Lý do mới của tôi", NOW.plusSeconds(120));

        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.code()).isNotEqualTo(first.code());
        assertThat(rows(memberA)).isEqualTo(2);
    }

    @Test
    void cancellingWithNothingPendingChangesNothing() {
        UUID none = topup(memberA);
        UUID cancelledOnce = topup(memberA);
        refunds.request(memberA, cancelledOnce, "Lý do của tôi ở đây", NOW);
        refunds.cancel(memberA, cancelledOnce, NOW.plusSeconds(1));
        UUID rejected = topup(memberA);
        RefundRequestView toReject = refunds.request(memberA, rejected, "Lý do của tôi ở đây", NOW.plusSeconds(2));
        decide(toReject.id(), "rejected", "Đã dùng dịch vụ", NOW.plusSeconds(3));
        Map<String, Object> before = jdbc.queryForMap("SELECT status, decided_at FROM refund_request WHERE id = ?",
                toReject.id());

        assertThatThrownBy(() -> refunds.cancel(memberA, none, NOW)).isInstanceOf(RefundRequestNotCancellableException.class);
        assertThatThrownBy(() -> refunds.cancel(memberA, cancelledOnce, NOW))
                .isInstanceOf(RefundRequestNotCancellableException.class);
        assertThatThrownBy(() -> refunds.cancel(memberA, rejected, NOW))
                .isInstanceOf(RefundRequestNotCancellableException.class);

        assertThat(jdbc.queryForMap("SELECT status, decided_at FROM refund_request WHERE id = ?", toReject.id()))
                .isEqualTo(before);
        assertThat(rows(memberA)).isEqualTo(2);
    }

    @Test
    void aRejectedRequestClosesTheRightAndTheLatestReadsItsRejection() {
        UUID id = topup(memberA);
        RefundRequestView request = refunds.request(memberA, id, "Lý do của tôi ở đây", NOW);
        Instant rejectedAt = Instant.parse("2026-11-22T08:00:00Z");
        decide(request.id(), "rejected", "Đã dùng dịch vụ", rejectedAt);

        assertThatThrownBy(() -> refunds.request(memberA, id, "Lý do thứ hai của tôi", NOW.plusSeconds(5)))
                .isInstanceOfSatisfying(RefundNotEligibleException.class,
                        e -> assertThat(e.right().refusal()).isEqualTo("previously_rejected"));
        RefundRequestView latest = refunds.latest(memberA, id).request();

        assertThat(latest.rejectedAt()).isEqualTo(rejectedAt);
        assertThat(latest.rejectionReason()).isEqualTo("Đã dùng dịch vụ");
        assertThat(latest.processingAt()).isNull();
        assertThat(latest.decisionNote()).isEqualTo("Đã dùng dịch vụ");
    }

    @Test
    void anApprovedRequestKeepsTheRightClosedAsAlreadyRefunded() {
        UUID id = payment(memberA, "plan_purchase", "basic:monthly", "succeeded", PAID);
        RefundRequestView request = refunds.request(memberA, id, "Lý do của tôi ở đây", NOW);
        decide(request.id(), "approved", null, NOW.plusSeconds(30));

        RefundEligibilityResponse eligibility = refunds.eligibility(memberA, id, NOW.plusSeconds(60));

        assertThat(eligibility.right().refusal()).isEqualTo("already_refunded");
        assertThat(eligibility.cancellable()).isFalse();
        assertThat(eligibility.request().processingAt()).isEqualTo(NOW.plusSeconds(30));
        assertThatThrownBy(() -> refunds.request(memberA, id, "Lý do thứ hai của tôi", NOW.plusSeconds(90)))
                .isInstanceOfSatisfying(RefundNotEligibleException.class,
                        e -> assertThat(e.right().refusal()).isEqualTo("already_refunded"));
    }

    @Test
    void thePendingPaymentIsNotSettledAndHasNoPaidTime() {
        UUID id = payment(memberA, "topup", "pkg-50k", "pending", null);

        RefundEligibilityResponse eligibility = refunds.eligibility(memberA, id, NOW);

        assertThat(eligibility.right().verdict()).isEqualTo("refused");
        assertThat(eligibility.right().refusal()).isEqualTo("not_settled");
        assertThat(eligibility.right().deadlineAt()).isNull();
        assertThat(eligibility.paidAt()).isNull();
        assertThat(eligibility.daysElapsed()).isNull();
        assertThat(eligibility.request()).isNull();
        assertThat(eligibility.cancellable()).isFalse();
    }

    @Test
    void eligibilityReadsThePaidTimeTheDaysElapsedAndTheTurnStanding() {
        UUID id = payment(memberA, "plan_purchase", "pro:yearly", "succeeded", PAID);

        RefundEligibilityResponse eligibility = refunds.eligibility(memberA, id, PAID.plus(Duration.ofHours(49)));

        assertThat(eligibility.paidAt()).isEqualTo(PAID);
        assertThat(eligibility.daysElapsed()).isEqualTo(2);
        assertThat(eligibility.right().windowDays()).isEqualTo(7);
        assertThat(eligibility.right().cycle()).isEqualTo("yearly");
        assertThat(eligibility.turns().enabled()).isTrue();
        assertThat(eligibility.turns().used()).isZero();
        assertThat(eligibility.turns().limit()).isEqualTo(2);
    }

    @Test
    void theChangeOfMindReadAnswersNothingWithTheSurfaceSwitchedOff() {
        settings.set(RefundSettings.SURFACE_ENABLED, false, operator);

        ChangeOfMindResponse answer = refunds.changeOfMind(memberA, NOW);

        assertThat(answer.surfaceEnabled()).isFalse();
        assertThat(answer.available()).isFalse();
        assertThat(answer.right()).isNull();
        assertThat(answer.turns()).isNull();
    }

    @Test
    void theChangeOfMindReadWithOnlyATopupHasNoRightButTheTurnStanding() {
        topup(memberA);

        ChangeOfMindResponse answer = refunds.changeOfMind(memberA, NOW);

        assertThat(answer.surfaceEnabled()).isTrue();
        assertThat(answer.available()).isTrue();
        assertThat(answer.right()).isNull();
        assertThat(answer.turns()).isNotNull();
    }

    @Test
    void theChangeOfMindReadJudgesTheNewestPlanPurchaseAndCarriesItsRequest() {
        payment(memberA, "plan_purchase", "basic:monthly", "succeeded", Instant.parse("2026-11-01T10:00:00Z"));
        UUID newest = payment(memberA, "plan_purchase", "basic:monthly", "succeeded", PAID);
        payment(memberA, "plan_purchase", "basic:monthly", "failed", null);
        RefundRequestView request = refunds.request(memberA, newest, "Lý do của tôi ở đây", NOW);

        ChangeOfMindResponse answer = refunds.changeOfMind(memberA, NOW);

        assertThat(answer.right().transactionId()).isEqualTo(newest);
        assertThat(answer.right().request().id()).isEqualTo(request.id());
        assertThat(answer.turns().used()).isOne();
    }

    @Test
    void anotherMembersPaymentAnswersNotFoundOnEveryRouteAndChangesNothing() {
        UUID theirs = topup(memberB);
        RefundRequestView request = refunds.request(memberB, theirs, "Lý do của tôi ở đây", NOW);
        UUID unknown = UUID.randomUUID();

        for (UUID target : List.of(theirs, unknown)) {
            assertThatThrownBy(() -> refunds.eligibility(memberA, target, NOW))
                    .isInstanceOf(RefundTransactionNotFoundException.class);
            assertThatThrownBy(() -> refunds.request(memberA, target, "Lý do của tôi ở đây", NOW))
                    .isInstanceOf(RefundTransactionNotFoundException.class);
            assertThatThrownBy(() -> refunds.cancel(memberA, target, NOW))
                    .isInstanceOf(RefundTransactionNotFoundException.class);
            assertThatThrownBy(() -> refunds.latest(memberA, target)).isInstanceOf(RefundTransactionNotFoundException.class);
        }

        assertThat(jdbc.queryForObject("SELECT status FROM refund_request WHERE id = ?", String.class, request.id()))
                .isEqualTo("pending");
        assertThat(rows(memberA)).isZero();
        assertThat(rows(memberB)).isOne();
    }

    @Test
    void theDatabaseRefusesRowsThatBreakTheRecordRules() {
        UUID id = topup(memberA);
        Timestamp at = Timestamp.from(NOW);
        String insert = "INSERT INTO refund_request (id, user_id, transaction_id, reason, status, decided_at, created_at,"
                + " updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), memberA, id, "x", "open", null, at, at))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), memberA, id, "x", "cancelled", null, at, at))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), memberA, id, "x", "pending", at, at, at))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), memberA, id, "", "pending", null, at, at))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), memberA, UUID.randomUUID(), "x", "pending",
                null, at, at)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(insert, UUID.randomUUID(), memberA, id, "x", "pending", null, at, at);
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), memberA, id, "y", "pending", null, at, at))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(rows(memberA)).isOne();
    }

    @Test
    void theLogNamesTheRequestIdAndCodeAndNeverTheReason() {
        UUID id = topup(memberA);
        String reason = "Lý do riêng tư của khách hàng";

        RefundRequestView request = refunds.request(memberA, id, reason, NOW);
        refunds.cancel(memberA, id, NOW.plusSeconds(5));

        List<String> lines = logLines.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(lines).contains("refund request created id=" + request.id() + " code=" + request.code(),
                "refund request cancelled id=" + request.id() + " code=" + request.code());
        assertThat(String.join("\n", lines)).doesNotContain(reason);
    }

    private static long number(String code) {
        return Long.parseLong(code.substring("HT-".length()));
    }

    private String outcome(CountDownLatch start, Callable<RefundRequestView> call) throws Exception {
        start.await();
        try {
            call.call();
            return "stored";
        } catch (RefundNotEligibleException e) {
            return e.right().refusal();
        }
    }

    private List<RefundRequestView> inParallel(Callable<RefundRequestView> one, Callable<RefundRequestView> two)
            throws InterruptedException, ExecutionException {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<RefundRequestView> first = pool.submit(() -> {
                start.await();
                return one.call();
            });
            Future<RefundRequestView> second = pool.submit(() -> {
                start.await();
                return two.call();
            });
            start.countDown();
            return List.of(first.get(), second.get());
        } finally {
            pool.shutdownNow();
        }
    }
}
