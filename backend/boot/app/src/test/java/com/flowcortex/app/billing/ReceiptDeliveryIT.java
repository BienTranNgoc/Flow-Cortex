package com.flowcortex.app.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.flowcortex.billing.receipt.ReceiptDeliveryJob;
import com.flowcortex.billing.receipt.ReceiptSettings;
import com.flowcortex.billing.webhook.PaymentWebhookEventRepository;
import com.flowcortex.mail.api.MailKey;
import com.flowcortex.mail.api.MailOutcome;
import com.flowcortex.mail.api.Mailer;
import com.flowcortex.mail.api.OutgoingMail;
import com.flowcortex.settings.api.Settings;
import com.flowcortex.user.api.AccountPreferences;
import com.flowcortex.user.api.UserService;
import com.flowcortex.user.api.UserView;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
class ReceiptDeliveryIT extends AbstractPostgresBootTest {

    private static final String PROVIDER = "momo";
    private static final String MERCHANT = "MOMOBKUN20180529";
    private static final byte[] RAW = {'{', '}'};
    private static final Instant PAID_AT = Instant.parse("2026-11-20T10:00:05Z");
    // Before any row the ledger writes during a run, so a pass here takes only the rows this class inserts.
    private static final Instant PASS_AT = Instant.parse("2026-11-24T10:00:00Z");
    // Older than anything another class could leave behind, so a pass takes this class's rows first.
    private static final Instant RECEIVED = Instant.now().minus(2, ChronoUnit.HOURS);

    private final UUID user = UUID.randomUUID();
    private final List<String> requestIds = new ArrayList<>();
    private final List<UUID> members = new ArrayList<>();
    private final ListAppender<ILoggingEvent> logLines = new ListAppender<>();
    private Logger jobLogger;

    @MockitoBean
    private Mailer mailer;

    @Autowired
    private PaymentLedger ledger;

    @Autowired
    private PaymentWebhookEventRepository inbox;

    @Autowired
    private LedgerApplier applier;

    @Autowired
    private ReceiptDeliveryJob job;

    @Autowired
    private Settings settings;

    @Autowired
    private UserService userService;

    @Autowired
    private AccountPreferences preferences;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void captureLog() {
        jobLogger = (Logger) LoggerFactory.getLogger(ReceiptDeliveryJob.class);
        logLines.start();
        jobLogger.addAppender(logLines);
        when(mailer.send(any())).thenReturn(MailOutcome.SENT);
    }

    @AfterEach
    void clean() {
        jobLogger.detachAppender(logLines);
        logLines.stop();
        jdbc.update("UPDATE vat_rate SET valid_to = NULL WHERE valid_from = DATE '1900-01-01'");
        jdbc.update("DELETE FROM app_setting WHERE key = ?", ReceiptSettings.EMAIL_ENABLED.name());
        jdbc.update("DELETE FROM app_setting_change WHERE key = ?", ReceiptSettings.EMAIL_ENABLED.name());
        for (String requestId : requestIds) {
            jdbc.update("DELETE FROM payment_webhook_event WHERE provider_request_id = ?", requestId);
        }
        jdbc.update("DELETE FROM payment_transaction_event WHERE transaction_id IN "
                + "(SELECT id FROM payment_transaction WHERE user_id = ?)", user);
        jdbc.update("DELETE FROM payment_transaction WHERE user_id = ?", user);
        for (UUID member : members) {
            jdbc.update("DELETE FROM payment_transaction WHERE user_id = ?", member);
        }
    }

    private OpenedPayment open() {
        return ledger.open(new OpenPayment(user, "topup", "pkg-50k", "Top-up", 50_000L, PROVIDER, MERCHANT,
                Optional.of(PaymentInstrument.WALLET)));
    }

    private void accept(String orderRef, String code, Instant receivedAt) {
        String requestId = UUID.randomUUID().toString();
        requestIds.add(requestId);
        PaymentProviderEvent event = new PaymentProviderEvent("1", PROVIDER, Kind.INBOUND_PAYMENT_CALLBACK,
                new Reference(orderRef, UUID.randomUUID().toString(), "4088878653"), code, "Message", 50_000L,
                PAID_AT, "qr", PAID_AT);
        inbox.insertAccepted(PROVIDER, requestId, event, RAW, "118.69.210.244", receivedAt);
    }

    private long deliveries(UUID transactionId) {
        return jdbc.queryForObject("SELECT count(*) FROM payment_receipt_delivery WHERE transaction_id = ?",
                Long.class, transactionId);
    }

    private Map<String, Object> delivery(UUID transactionId) {
        return jdbc.queryForMap("SELECT user_id, created_at, next_attempt_at, claimed_at, attempts, outcome, sent_at "
                + "FROM payment_receipt_delivery WHERE transaction_id = ?", transactionId);
    }

    private void insertDelivery(UUID transactionId, String outcome, Timestamp sentAt) {
        jdbc.update("INSERT INTO payment_receipt_delivery (transaction_id, user_id, created_at, next_attempt_at, "
                + "outcome, sent_at) VALUES (?, ?, now(), now(), ?, ?)", transactionId, user, outcome, sentAt);
    }

    private UserView member(String language) {
        UserView member = userService.createInitialAdmin("receipt-delivery-" + UUID.randomUUID() + "@example.com",
                "password123", "Nguyễn Văn A");
        members.add(member.id());
        if (language != null) {
            preferences.setLocale(member.id(), language);
        }
        return member;
    }

    /** A collected payment of {@code owner} with a delivery row as the ledger writes it, unless overridden. */
    private UUID dueRow(UUID owner, Instant nextAttemptAt, int attempts, Instant claimedAt) {
        members.add(owner);
        UUID transactionId = ReceiptFixtures.collected(jdbc, owner, "Nạp 1.000 credit", 100_000, 90_909, 9_091,
                PAID_AT);
        jdbc.update("INSERT INTO payment_receipt_delivery (transaction_id, user_id, created_at, next_attempt_at, "
                + "claimed_at, attempts) VALUES (?, ?, ?, ?, ?, ?)", transactionId, owner,
                Timestamp.from(PAID_AT), Timestamp.from(nextAttemptAt),
                claimedAt == null ? null : Timestamp.from(claimedAt), attempts);
        return transactionId;
    }

    private List<OutgoingMail> sentFor(UUID transactionId) {
        String orderRef = ReceiptFixtures.orderRef(jdbc, transactionId);
        ArgumentCaptor<OutgoingMail> captor = ArgumentCaptor.forClass(OutgoingMail.class);
        verify(mailer, atLeast(0)).send(captor.capture());
        return captor.getAllValues().stream()
                .filter(mail -> mail.key() == MailKey.PAYMENT_RECEIPT && mail.values().get(1).equals(orderRef))
                .toList();
    }

    private double counted(String outcome) {
        return meters.counter("billing.receipt.delivery", "outcome", outcome).count();
    }

    private List<ILoggingEvent> lines() {
        return logLines.list.stream().filter(e -> e.getFormattedMessage().startsWith("billing.receipt.delivery"))
                .toList();
    }

    @Test
    void aCollectionWithTheSwitchOnWritesOneDueRowAndNoSendYet() {
        OpenedPayment opened = open();
        accept(opened.orderRef(), "0", RECEIVED);

        applier.applyPending();

        assertThat(deliveries(opened.transactionId())).isEqualTo(1);
        Map<String, Object> row = delivery(opened.transactionId());
        assertThat(row).containsEntry("user_id", user)
                .containsEntry("attempts", 0)
                .containsEntry("claimed_at", null)
                .containsEntry("outcome", null)
                .containsEntry("sent_at", null);
        assertThat(row.get("next_attempt_at")).isEqualTo(row.get("created_at"));
    }

    @Test
    void theSameCallbackReplayedLeavesOneRow() {
        OpenedPayment opened = open();
        accept(opened.orderRef(), "0", RECEIVED);
        applier.applyPending();
        accept(opened.orderRef(), "0", RECEIVED.plusSeconds(1));

        applier.applyPending();

        assertThat(deliveries(opened.transactionId())).isEqualTo(1);
    }

    @Test
    void aCollectionWithTheSwitchOffWritesNoRow() {
        settings.set(ReceiptSettings.EMAIL_ENABLED, false, UUID.randomUUID());
        OpenedPayment opened = open();
        accept(opened.orderRef(), "0", RECEIVED);

        applier.applyPending();

        assertThat(jdbc.queryForObject("SELECT status FROM payment_transaction WHERE id = ?", String.class,
                opened.transactionId())).isEqualTo("succeeded");
        assertThat(deliveries(opened.transactionId())).isZero();
    }

    @Test
    void aFailureCallbackWritesNoRow() {
        OpenedPayment opened = open();
        accept(opened.orderRef(), "1006", RECEIVED);

        applier.applyPending();

        assertThat(jdbc.queryForObject("SELECT status FROM payment_transaction WHERE id = ?", String.class,
                opened.transactionId())).isEqualTo("failed");
        assertThat(deliveries(opened.transactionId())).isZero();
    }

    @Test
    void aSettlementThatIsRolledBackKeepsNeitherTheMoveNorTheRow() {
        OpenedPayment opened = open();
        accept(opened.orderRef(), "0", RECEIVED);
        jdbc.update("UPDATE vat_rate SET valid_to = DATE '2026-01-01' WHERE valid_from = DATE '1900-01-01'");

        applier.applyPending();

        assertThat(jdbc.queryForObject("SELECT status FROM payment_transaction WHERE id = ?", String.class,
                opened.transactionId())).isEqualTo("pending");
        assertThat(deliveries(opened.transactionId())).isZero();
    }

    @Test
    void theDatabaseRefusesAnUnknownOutcomeASentTimeWithoutSentAndASecondRowForOnePayment() {
        OpenedPayment opened = open();

        assertThatThrownBy(() -> insertDelivery(opened.transactionId(), "bounced", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertDelivery(opened.transactionId(), "failed", Timestamp.from(Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertDelivery(opened.transactionId(), "sent", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        insertDelivery(opened.transactionId(), null, null);
        assertThatThrownBy(() -> insertDelivery(opened.transactionId(), null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(deliveries(opened.transactionId())).isEqualTo(1);
    }

    @Test
    void deletingAPaymentDeletesItsDeliveryRow() {
        OpenedPayment opened = open();
        insertDelivery(opened.transactionId(), null, null);

        jdbc.update("DELETE FROM payment_transaction_event WHERE transaction_id = ?", opened.transactionId());
        jdbc.update("DELETE FROM payment_transaction WHERE id = ?", opened.transactionId());

        assertThat(deliveries(opened.transactionId())).isZero();
    }

    @Test
    void aDueRowIsMailedOnceAndMarkedSentWithItsTime() {
        UserView member = member("en");
        UUID transactionId = dueRow(member.id(), PASS_AT.minusSeconds(60), 0, null);
        double before = counted("sent");

        job.runAt(PASS_AT);
        job.runAt(PASS_AT.plusSeconds(60));

        assertThat(sentFor(transactionId)).hasSize(1).singleElement().satisfies(mail -> {
            assertThat(mail.to()).isEqualTo(member.email());
            assertThat(mail.locale()).isEqualTo(Locale.ENGLISH);
            assertThat(mail.attachments()).hasSize(1);
        });
        Map<String, Object> row = delivery(transactionId);
        assertThat(row).containsEntry("outcome", "sent").containsEntry("attempts", 1).containsEntry("claimed_at", null);
        assertThat(((Timestamp) row.get("sent_at")).toInstant()).isEqualTo(PASS_AT);
        assertThat(counted("sent")).isEqualTo(before + 1);
    }

    @Test
    void aRowWrittenBeforeTheSwitchWasTurnedOffIsStillSent() {
        UUID transactionId = dueRow(member(null).id(), PASS_AT.minusSeconds(60), 0, null);
        settings.set(ReceiptSettings.EMAIL_ENABLED, false, UUID.randomUUID());

        job.runAt(PASS_AT);

        assertThat(sentFor(transactionId)).hasSize(1);
        assertThat(delivery(transactionId)).containsEntry("outcome", "sent");
    }

    @Test
    void aRowNotYetDueIsLeftAlone() {
        UUID transactionId = dueRow(member(null).id(), PASS_AT.plusSeconds(60), 1, null);

        job.runAt(PASS_AT);

        assertThat(sentFor(transactionId)).isEmpty();
        assertThat(delivery(transactionId)).containsEntry("attempts", 1).containsEntry("outcome", null);
    }

    @Test
    void aRefusedSendIsReleasedAndDueThirtyMinutesLaterAndCounted() {
        UUID transactionId = dueRow(member("vi").id(), PASS_AT.minusSeconds(60), 0, null);
        when(mailer.send(any())).thenReturn(MailOutcome.FAILED);
        double before = counted("retry");

        job.runAt(PASS_AT);

        Map<String, Object> row = delivery(transactionId);
        assertThat(row).containsEntry("attempts", 1).containsEntry("claimed_at", null).containsEntry("outcome", null);
        assertThat(((Timestamp) row.get("next_attempt_at")).toInstant()).isEqualTo(PASS_AT.plus(Duration.ofMinutes(30)));
        assertThat(counted("retry")).isEqualTo(before + 1);
        assertThat(lines()).singleElement().satisfies(line -> {
            assertThat(line.getLevel()).isEqualTo(Level.WARN);
            assertThat(line.getFormattedMessage()).contains("outcome=retry", "transaction=" + transactionId);
        });
    }

    @Test
    void aThrownExceptionIsRetriedAndTheLineCarriesTheClassAndNoPersonalData() {
        UserView member = member("vi");
        UUID transactionId = dueRow(member.id(), PASS_AT.minusSeconds(60), 0, null);
        when(mailer.send(any())).thenThrow(new IllegalStateException("relay said no to " + member.email()));

        job.runAt(PASS_AT);

        assertThat(delivery(transactionId)).containsEntry("attempts", 1).containsEntry("outcome", null);
        assertThat(lines()).singleElement().satisfies(line -> assertThat(line.getFormattedMessage())
                .contains("transaction=" + transactionId, "IllegalStateException")
                .doesNotContain(member.email(), "Nguyễn", "100.000", "100000"));
    }

    @Test
    void theSixthFailureGivesUpWithAnErrorLineThatNamesOnlyTheTransaction() {
        UserView member = member("vi");
        UUID transactionId = dueRow(member.id(), PASS_AT.minusSeconds(60), 5, null);
        when(mailer.send(any())).thenReturn(MailOutcome.FAILED);
        double before = counted("failed");

        job.runAt(PASS_AT);
        job.runAt(PASS_AT.plus(Duration.ofHours(5)));

        assertThat(delivery(transactionId)).containsEntry("outcome", "failed").containsEntry("attempts", 6)
                .containsEntry("sent_at", null);
        assertThat(counted("failed")).isEqualTo(before + 1);
        assertThat(sentFor(transactionId)).hasSize(1);
        assertThat(lines()).singleElement().satisfies(line -> {
            assertThat(line.getLevel()).isEqualTo(Level.ERROR);
            assertThat(line.getFormattedMessage()).contains("outcome=failed", "transaction=" + transactionId)
                    .doesNotContain(member.email());
        });
    }

    @Test
    void aRowClaimedFiveMinutesAgoIsNotSentAndOneClaimedElevenMinutesAgoIs() {
        UUID held = dueRow(member(null).id(), PASS_AT.minusSeconds(3600), 1, PASS_AT.minus(Duration.ofMinutes(5)));
        UUID stale = dueRow(member(null).id(), PASS_AT.minusSeconds(3600), 1, PASS_AT.minus(Duration.ofMinutes(11)));

        job.runAt(PASS_AT);

        assertThat(sentFor(held)).isEmpty();
        assertThat(delivery(held)).containsEntry("attempts", 1).containsEntry("outcome", null);
        assertThat(sentFor(stale)).hasSize(1);
        assertThat(delivery(stale)).containsEntry("attempts", 2).containsEntry("outcome", "sent");
    }

    @Test
    void aPassThatLostItsClaimChangesNothingWhenItsSendFinallyFails() {
        UUID transactionId = dueRow(member(null).id(), PASS_AT.minusSeconds(60), 0, null);
        Instant later = PASS_AT.plus(Duration.ofMinutes(11));
        AtomicInteger calls = new AtomicInteger();
        when(mailer.send(any())).thenAnswer(call -> {
            if (calls.incrementAndGet() == 1) {
                job.runAt(later);
                return MailOutcome.FAILED;
            }
            return MailOutcome.SENT;
        });
        double retries = counted("retry");

        job.runAt(PASS_AT);

        Map<String, Object> row = delivery(transactionId);
        assertThat(row).containsEntry("outcome", "sent").containsEntry("attempts", 2);
        assertThat(((Timestamp) row.get("sent_at")).toInstant()).isEqualTo(later);
        assertThat(counted("retry")).isEqualTo(retries);
        assertThat(lines()).anySatisfy(line -> assertThat(line.getFormattedMessage())
                .contains("outcome=claim_lost", "transaction=" + transactionId));
    }

    @Test
    void aMemberWhoseRowIsGoneIsMarkedNoAddressAndNothingIsSent() {
        UUID transactionId = dueRow(UUID.randomUUID(), PASS_AT.minusSeconds(60), 0, null);
        double before = counted("no_address");

        job.runAt(PASS_AT);

        assertThat(sentFor(transactionId)).isEmpty();
        assertThat(delivery(transactionId)).containsEntry("outcome", "no_address").containsEntry("claimed_at", null);
        assertThat(counted("no_address")).isEqualTo(before + 1);
    }

    @Test
    void aPassNeverTouchesARowThatAlreadyHasItsOutcome() {
        UserView member = member(null);
        UUID transactionId = dueRow(member.id(), PASS_AT.minusSeconds(60), 0, null);
        jdbc.update("UPDATE payment_receipt_delivery SET outcome = 'no_address' WHERE transaction_id = ?",
                transactionId);

        job.runAt(PASS_AT);

        verify(mailer, never()).send(Mockito.argThat(mail -> mail != null && mail.to().equals(member.email())));
        assertThat(delivery(transactionId)).containsEntry("outcome", "no_address").containsEntry("attempts", 0);
    }
}
