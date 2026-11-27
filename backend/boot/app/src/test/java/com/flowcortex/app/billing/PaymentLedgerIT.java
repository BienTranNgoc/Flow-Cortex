package com.flowcortex.app.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.billing.ledger.OpenPayment;
import com.flowcortex.billing.ledger.OpenedPayment;
import com.flowcortex.billing.ledger.PaymentLedger;
import com.flowcortex.billing.ledger.PaymentOpeningRefusedException;
import com.flowcortex.billing.provider.PaymentInstrument;
import com.flowcortex.billing.provider.PaymentOrderLookup;
import com.flowcortex.billing.provider.PaymentOrderLookup.RecordedOrder;
import com.flowcortex.content.api.PublicationIndex;
import com.flowcortex.content.api.PublicationIndex.PublicationRef;
import com.flowcortex.content.document.ContentDocumentService;
import com.flowcortex.content.document.PublishedDocuments;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class PaymentLedgerIT extends AbstractPostgresBootTest {

    private static final String PROVIDER = "momo";
    private static final String MERCHANT = "MOMOBKUN20180529";
    private static final String ORDER_REF = "fc_0123456789abcdef0123456789abcdef";
    private static final String OTHER_ORDER_REF = "fc_fedcba9876543210fedcba9876543210";

    private final UUID user = UUID.randomUUID();

    @Autowired
    private PaymentLedger ledger;

    @Autowired
    private PaymentOrderLookup lookup;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PublicationIndex publications;

    @Autowired
    private ContentDocumentService documents;

    @Autowired
    private PublishedDocuments publishedDocuments;

    private PaymentTermsFixture terms;

    @BeforeEach
    void noTermsPublished() {
        terms = new PaymentTermsFixture(documents, publishedDocuments, jdbc);
        terms.clear();
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM payment_transaction_event WHERE transaction_id IN "
                + "(SELECT id FROM payment_transaction WHERE user_id = ?)", user);
        jdbc.update("DELETE FROM payment_transaction WHERE user_id = ?", user);
        terms.clear();
    }

    private OpenPayment topUp() {
        return new OpenPayment(user, "topup", "pkg-50k", "Nạp 10.000 credit", 50_000L, PROVIDER, MERCHANT,
                Optional.of(PaymentInstrument.WALLET));
    }

    private long paymentRows() {
        return jdbc.queryForObject("SELECT count(*) FROM payment_transaction WHERE user_id = ?", Long.class, user);
    }

    private long historyRows() {
        return jdbc.queryForObject("SELECT count(*) FROM payment_transaction_event e "
                + "JOIN payment_transaction t ON t.id = e.transaction_id WHERE t.user_id = ?", Long.class, user);
    }

    @Test
    void openingATopUpStoresOnePendingPaymentWithItsFirstHistoryEntry() {
        OpenedPayment opened = ledger.open(topUp());

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM payment_transaction WHERE id = ?",
                opened.transactionId());
        assertThat(row).containsEntry("user_id", user)
                .containsEntry("kind", "topup")
                .containsEntry("item_ref", "pkg-50k")
                .containsEntry("description", "Nạp 10.000 credit")
                .containsEntry("amount_vnd", 50_000L)
                .containsEntry("currency", "VND")
                .containsEntry("provider", PROVIDER)
                .containsEntry("merchant_code", MERCHANT)
                .containsEntry("instrument", "wallet")
                .containsEntry("status", "pending")
                .containsEntry("order_ref", opened.orderRef());
        assertThat(row.get("request_ref").toString()).isEqualTo(opened.requestRef());
        assertThat(row.get("succeeded_at")).isNull();
        assertThat(row.get("provider_status_at")).isNull();
        assertThat(opened.orderRef()).matches("^fc_[0-9a-f]{32}$");
        assertThat(UUID.fromString(opened.requestRef())).isNotNull();

        List<Map<String, Object>> history = jdbc.queryForList(
                "SELECT * FROM payment_transaction_event WHERE transaction_id = ?", opened.transactionId());
        assertThat(history).hasSize(1);
        assertThat(history.get(0)).containsEntry("from_status", null)
                .containsEntry("to_status", "pending")
                .containsEntry("reason", "opened")
                .containsEntry("provider_result_code", null)
                .containsEntry("evidence_ref", null);
        assertThat(history.get(0).get("occurred_at")).isEqualTo(row.get("created_at"));
    }

    @Test
    void anOpenedPaymentPinsTheNewestPublicationOfThePaymentTerms() {
        terms.publish(1);
        UUID newest = terms.publish(2);

        OpenedPayment opened = ledger.open(topUp());

        assertThat(jdbc.queryForObject("SELECT terms_revision_id FROM payment_transaction WHERE id = ?", UUID.class,
                opened.transactionId())).isEqualTo(newest);
    }

    @Test
    void anOpenedPaymentWithNoPublishedTermsPinsNothingAndIsStored() {
        OpenedPayment opened = ledger.open(topUp());

        assertThat(jdbc.queryForObject("SELECT terms_revision_id FROM payment_transaction WHERE id = ?", UUID.class,
                opened.transactionId())).isNull();
        assertThat(paymentRows()).isEqualTo(1);
    }

    @Test
    void thePublicationIndexListsPaymentTermsNewestFirstAndNothingForAnotherDocument() {
        UUID first = terms.publish(1);
        UUID second = terms.publish(2);

        assertThat(publications.publications(PaymentTermsFixture.SLUG)).extracting(PublicationRef::id)
                .containsExactly(second, first);
        assertThat(publications.publications("legal-index")).isEmpty();
        assertThat(publications.publications("internal-notes")).isEmpty();
    }

    @Test
    void anOpeningWithoutAnInstrumentStoresNone() {
        OpenedPayment opened = ledger.open(new OpenPayment(user, "plan_purchase", "basic:monthly", "Basic plan",
                120_000L, PROVIDER, MERCHANT, Optional.empty()));

        assertThat(jdbc.queryForObject("SELECT instrument FROM payment_transaction WHERE id = ?", String.class,
                opened.transactionId())).isNull();
    }

    @Test
    void twoOpeningsOfTheSameItemDiffer() {
        OpenedPayment first = ledger.open(topUp());
        OpenedPayment second = ledger.open(topUp());

        assertThat(first.transactionId()).isNotEqualTo(second.transactionId());
        assertThat(first.orderRef()).isNotEqualTo(second.orderRef());
        assertThat(first.requestRef()).isNotEqualTo(second.requestRef());
        assertThat(paymentRows()).isEqualTo(2);
    }

    @Test
    void anInvalidOpeningCannotBeBuiltSoNothingIsStored() {
        assertThatThrownBy(() -> new OpenPayment(user, "topup", "pkg-50k", "Nạp credit", 0L, PROVIDER, MERCHANT,
                Optional.empty()))
                .isInstanceOfSatisfying(PaymentOpeningRefusedException.class,
                        e -> assertThat(e.field()).isEqualTo("amountVnd"));

        assertThat(paymentRows()).isZero();
        assertThat(historyRows()).isZero();
    }

    @Test
    void aSecondPaymentWithTheSameOrderReferenceIsRefused() {
        insert(ORDER_REF, UUID.randomUUID(), "pending", "topup", 50_000L, "VND", null);

        assertThatThrownBy(() -> insert(ORDER_REF, UUID.randomUUID(), "pending", "topup", 50_000L, "VND", null))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void aSecondPaymentWithTheSameProviderAndRequestReferenceIsRefused() {
        UUID request = UUID.randomUUID();
        insert(ORDER_REF, request, "pending", "topup", 50_000L, "VND", null);

        assertThatThrownBy(() -> insert(OTHER_ORDER_REF, request, "pending", "topup", 50_000L, "VND", null))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void anOrderReferenceNotOfTheFcShapeIsRefused() {
        assertCheckViolation(() -> insert("xo_1", UUID.randomUUID(), "pending", "topup", 50_000L, "VND", null));
    }

    @Test
    void aStatusOutsideTheSevenIsRefused() {
        assertCheckViolation(
                () -> insert(ORDER_REF, UUID.randomUUID(), "cancelled", "topup", 50_000L, "VND", null));
    }

    @Test
    void aKindOutsideTheTwoIsRefused() {
        assertCheckViolation(() -> insert(ORDER_REF, UUID.randomUUID(), "pending", "refund", 50_000L, "VND", null));
    }

    @Test
    void anAmountOfZeroIsRefused() {
        assertCheckViolation(() -> insert(ORDER_REF, UUID.randomUUID(), "pending", "topup", 0L, "VND", null));
    }

    @Test
    void aCurrencyOtherThanVndIsRefused() {
        assertCheckViolation(() -> insert(ORDER_REF, UUID.randomUUID(), "pending", "topup", 50_000L, "USD", null));
    }

    @Test
    void aPaidTimeOnAPendingPaymentIsRefused() {
        assertCheckViolation(() -> insert(ORDER_REF, UUID.randomUUID(), "pending", "topup", 50_000L, "VND",
                Timestamp.from(Instant.now())));
    }

    @Test
    void aPaidTimeOnASucceededPaymentIsAccepted() {
        insert(ORDER_REF, UUID.randomUUID(), "succeeded", "topup", 50_000L, "VND", Timestamp.from(Instant.now()));

        assertThat(paymentRows()).isEqualTo(1);
    }

    @Test
    void anOpeningHistoryEntryNeedsNoPreviousStatusAndOnlyAnOpeningHasNone() {
        OpenedPayment opened = ledger.open(topUp());

        assertCheckViolation(() -> insertHistory(opened.transactionId(), null, "pending", "provider_code:0"));
        assertCheckViolation(() -> insertHistory(opened.transactionId(), "pending", "succeeded", "opened"));
        insertHistory(opened.transactionId(), "pending", "succeeded", "provider_code:0");
        assertThat(historyRows()).isEqualTo(2);
    }

    @Test
    void aRecordedOrderIsFoundByItsProviderAndReference() {
        OpenedPayment opened = ledger.open(topUp());

        assertThat(lookup.find(PROVIDER, opened.orderRef())).contains(new RecordedOrder(50_000L, MERCHANT));
    }

    @Test
    void anotherProvidersLookupOfTheSameReferenceFindsNothing() {
        OpenedPayment opened = ledger.open(topUp());

        assertThat(lookup.find("onepay", opened.orderRef())).isEmpty();
    }

    @Test
    void anUnknownReferenceFindsNothing() {
        assertThat(lookup.find(PROVIDER, "fc_ffffffffffffffffffffffffffffffff")).isEmpty();
    }

    private void insert(String orderRef, UUID requestRef, String status, String kind, long amount, String currency,
            Timestamp succeededAt) {
        boolean paid = succeededAt != null;
        long tax = paid ? amount / 11 : 0;
        UUID rate = paid ? jdbc.queryForObject("SELECT id FROM vat_rate WHERE valid_from = DATE '1900-01-01'",
                UUID.class) : null;
        jdbc.update("INSERT INTO payment_transaction (id, user_id, kind, item_ref, description, amount_vnd, currency, "
                + "provider, merchant_code, order_ref, request_ref, status, created_at, updated_at, succeeded_at, "
                + "base_amount_vnd, tax_amount_vnd, tax_rate_bps, tax_rate_id, tax_inclusive) "
                + "VALUES (?, ?, ?, 'pkg-50k', 'Top-up', ?, ?, ?, ?, ?, ?, ?, now(), now(), ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), user, kind, amount, currency, PROVIDER, MERCHANT, orderRef, requestRef, status,
                succeededAt, paid ? amount - tax : null, paid ? tax : null, paid ? 1000 : null, rate,
                paid ? Boolean.TRUE : null);
    }

    private void insertHistory(UUID transactionId, String from, String to, String reason) {
        jdbc.update("INSERT INTO payment_transaction_event (id, transaction_id, from_status, to_status, reason, "
                + "occurred_at) VALUES (?, ?, ?, ?, ?, now())", UUID.randomUUID(), transactionId, from, to, reason);
    }

    private static void assertCheckViolation(ThrowingCallable write) {
        assertThatThrownBy(write)
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("check constraint");
    }
}
