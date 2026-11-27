package com.flowcortex.app.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.billing.history.HistoryQuery;
import com.flowcortex.billing.history.TransactionHistoryReader;
import com.flowcortex.billing.history.TransactionHistoryReader.Detail;
import com.flowcortex.billing.history.TransactionHistoryReader.Page;
import com.flowcortex.billing.history.TransactionHistoryReader.Row;
import com.flowcortex.billing.ledger.OpenPayment;
import com.flowcortex.billing.ledger.OpenedPayment;
import com.flowcortex.billing.ledger.PaymentLedger;
import com.flowcortex.billing.ledger.Timeline.TimelineEvent;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class TransactionHistoryIT extends AbstractPostgresBootTest {

    private static final String PROVIDER = "momo";
    private static final String MERCHANT = "MOMOBKUN20180529";
    private static final Instant BASE = Instant.parse("2026-11-20T10:00:00Z");

    private final UUID memberA = UUID.randomUUID();
    private final UUID memberB = UUID.randomUUID();

    @Autowired
    private PaymentLedger ledger;

    @Autowired
    private TransactionHistoryReader reader;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void clean() {
        for (UUID member : List.of(memberA, memberB)) {
            jdbc.update("DELETE FROM payment_transaction_event WHERE transaction_id IN "
                    + "(SELECT id FROM payment_transaction WHERE user_id = ?)", member);
            jdbc.update("DELETE FROM payment_transaction WHERE user_id = ?", member);
        }
    }

    private OpenedPayment open(UUID member, long amount) {
        return ledger.open(new OpenPayment(member, "topup", "pkg-50k", "Nạp 10.000 credit", amount, PROVIDER,
                MERCHANT, Optional.empty()));
    }

    /** Pins the opening time of a payment and of its opening history entry. */
    private void openedAt(OpenedPayment payment, Instant at) {
        jdbc.update("UPDATE payment_transaction SET created_at = ?, updated_at = ? WHERE id = ?",
                Timestamp.from(at), Timestamp.from(at), payment.transactionId());
        jdbc.update("UPDATE payment_transaction_event SET occurred_at = ? WHERE transaction_id = ?",
                Timestamp.from(at), payment.transactionId());
    }

    private void moveTo(OpenedPayment payment, String from, String to, String code, Instant at, boolean paid) {
        jdbc.update("""
                UPDATE payment_transaction SET status = ?, provider_result_code = ?, provider_transaction_id = ?,
                    provider_pay_type = ?, provider_status_at = ?, updated_at = ?, succeeded_at = ?,
                    base_amount_vnd = CASE WHEN ? THEN amount_vnd - amount_vnd / 11 END,
                    tax_amount_vnd = CASE WHEN ? THEN amount_vnd / 11 END,
                    tax_rate_bps = CASE WHEN ? THEN 1000 END,
                    tax_rate_id = CASE WHEN ? THEN (SELECT id FROM vat_rate WHERE valid_from = DATE '1900-01-01') END,
                    tax_inclusive = CASE WHEN ? THEN TRUE END
                WHERE id = ?
                """,
                to, code, paid ? "4088878653" : null, paid ? "webApp" : null, Timestamp.from(at),
                Timestamp.from(at), paid ? Timestamp.from(at) : null, paid, paid, paid, paid, paid,
                payment.transactionId());
        jdbc.update("""
                INSERT INTO payment_transaction_event (id, transaction_id, from_status, to_status, reason,
                    provider_result_code, evidence_ref, provider_timestamp, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), payment.transactionId(), from, to, "provider_code:" + code, code,
                UUID.randomUUID().toString(), Timestamp.from(at), Timestamp.from(at));
    }

    private void setStatusOnly(OpenedPayment payment, String status) {
        jdbc.update("UPDATE payment_transaction SET status = ? WHERE id = ?", status, payment.transactionId());
    }

    @Test
    void aMemberWithTwentyThreePaymentsReadsTwentyNewestFirstThenTheThreeOldest() {
        List<OpenedPayment> opened = new ArrayList<>();
        for (int i = 0; i < 23; i++) {
            OpenedPayment payment = open(memberA, 50_000L + i);
            openedAt(payment, BASE.plusSeconds(60L * i));
            opened.add(payment);
        }

        Page first = reader.page(memberA, new HistoryQuery(0, 20, false));
        Page second = reader.page(memberA, new HistoryQuery(1, 20, false));

        assertThat(first.total()).isEqualTo(23);
        assertThat(first.rows()).hasSize(20);
        assertThat(first.rows().get(0).id()).isEqualTo(opened.get(22).transactionId());
        assertThat(first.rows().get(19).id()).isEqualTo(opened.get(3).transactionId());
        assertThat(second.total()).isEqualTo(23);
        assertThat(second.rows()).extracting(Row::id).containsExactly(
                opened.get(2).transactionId(), opened.get(1).transactionId(), opened.get(0).transactionId());
    }

    @Test
    void aRowCarriesTheFieldsOfTheList() {
        OpenedPayment payment = open(memberA, 50_000L);
        openedAt(payment, BASE);
        moveTo(payment, "pending", "succeeded", "0", BASE.plusSeconds(5), true);

        Row row = reader.page(memberA, new HistoryQuery(0, 20, false)).rows().get(0);

        assertThat(row.id()).isEqualTo(payment.transactionId());
        assertThat(row.kind()).isEqualTo("topup");
        assertThat(row.description()).isEqualTo("Nạp 10.000 credit");
        assertThat(row.amountVnd()).isEqualTo(50_000L);
        assertThat(row.status()).isEqualTo("succeeded");
        assertThat(row.openedAt()).isEqualTo(BASE);
        assertThat(row.succeededAt()).isEqualTo(BASE.plusSeconds(5));
    }

    @Test
    void theSettledFilterListsAndCountsOnlyPaymentsWhereMoneyReachedUs() {
        String[] statuses = {"succeeded", "partially_refunded", "refunded", "pending", "failed", "expired",
                "disputed"};
        for (int i = 0; i < statuses.length; i++) {
            OpenedPayment payment = open(memberA, 50_000L);
            openedAt(payment, BASE.plusSeconds(i));
            setStatusOnly(payment, statuses[i]);
        }

        Page settled = reader.page(memberA, new HistoryQuery(0, 20, true));
        Page all = reader.page(memberA, new HistoryQuery(0, 20, false));

        assertThat(settled.total()).isEqualTo(3);
        assertThat(settled.rows()).extracting(Row::status)
                .containsExactlyInAnyOrder("succeeded", "partially_refunded", "refunded");
        assertThat(all.total()).isEqualTo(7);
    }

    @Test
    void aMemberNeverSeesAnotherMembersPayments() {
        OpenedPayment own = open(memberA, 50_000L);
        OpenedPayment others = open(memberB, 60_000L);

        Page page = reader.page(memberA, new HistoryQuery(0, 20, false));

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.rows()).extracting(Row::id).containsExactly(own.transactionId());
        assertThat(reader.findOwn(memberA, others.transactionId())).isEmpty();
        assertThat(reader.findOwnByOrder(memberA, others.orderRef())).isEmpty();
        assertThat(reader.findOwn(memberB, others.transactionId())).isPresent();
    }

    @Test
    void anUnknownPaymentIsEmpty() {
        assertThat(reader.findOwn(memberA, UUID.randomUUID())).isEmpty();
        assertThat(reader.findOwnByOrder(memberA, "fc_" + "0".repeat(32))).isEmpty();
    }

    @Test
    void aPaidPaymentReadsWithItsProviderFactsAndTheOpenedPaidTimeline() {
        OpenedPayment payment = open(memberA, 50_000L);
        openedAt(payment, BASE);
        moveTo(payment, "pending", "succeeded", "0", BASE.plusSeconds(5), true);

        Detail detail = reader.findOwn(memberA, payment.transactionId()).orElseThrow();

        assertThat(detail.status()).isEqualTo("succeeded");
        assertThat(detail.orderRef()).isEqualTo(payment.orderRef());
        assertThat(detail.provider()).isEqualTo(PROVIDER);
        assertThat(detail.providerTransactionId()).isEqualTo("4088878653");
        assertThat(detail.providerPayType()).isEqualTo("webApp");
        assertThat(detail.instrument()).isNull();
        assertThat(detail.outcomeCode()).isNull();
        assertThat(detail.timeline()).containsExactly(
                new TimelineEvent(BASE, "opened", null), new TimelineEvent(BASE.plusSeconds(5), "paid", null));
    }

    @Test
    void aFailedPaymentCarriesItsOutcomeCodeAndFailureEvent() {
        OpenedPayment payment = open(memberA, 50_000L);
        openedAt(payment, BASE);
        moveTo(payment, "pending", "failed", "1006", BASE.plusSeconds(7), false);

        Detail detail = reader.findOwn(memberA, payment.transactionId()).orElseThrow();

        assertThat(detail.status()).isEqualTo("failed");
        assertThat(detail.outcomeCode()).isEqualTo("1006");
        assertThat(detail.timeline()).extracting(TimelineEvent::kind, TimelineEvent::code)
                .containsExactly(tuple("opened", null), tuple("payment_failed", "1006"));
    }

    @Test
    void aPendingPaymentIsFoundByItsOrderReference() {
        OpenedPayment payment = open(memberA, 50_000L);

        Detail detail = reader.findOwnByOrder(memberA, payment.orderRef()).orElseThrow();

        assertThat(detail.id()).isEqualTo(payment.transactionId());
        assertThat(detail.status()).isEqualTo("pending");
        assertThat(detail.timeline()).extracting(TimelineEvent::kind).containsExactly("opened");
    }
}
