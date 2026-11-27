package com.flowcortex.app.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.billing.history.AgreementReader;
import com.flowcortex.billing.history.AgreementReader.Agreement;
import com.flowcortex.billing.history.AgreementReader.AgreementRow;
import com.flowcortex.billing.history.AgreementReader.Page;
import com.flowcortex.billing.history.HistoryQuery;
import com.flowcortex.billing.ledger.OpenPayment;
import com.flowcortex.billing.ledger.PaymentLedger;
import com.flowcortex.billing.provider.PaymentInstrument;
import com.flowcortex.billing.terms.TermsUnavailableException;
import com.flowcortex.content.document.ContentDocumentService;
import com.flowcortex.content.document.PublishedDocuments;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class AgreementsIT extends AbstractPostgresBootTest {

    private static final HistoryQuery FIRST_PAGE = new HistoryQuery(0, 20, false);

    private final UUID userA = UUID.randomUUID();
    private final UUID userB = UUID.randomUUID();

    @Autowired
    private AgreementReader reader;

    @Autowired
    private PaymentLedger ledger;

    @Autowired
    private JdbcTemplate jdbc;

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
                + "(SELECT id FROM payment_transaction WHERE user_id IN (?, ?))", userA, userB);
        jdbc.update("DELETE FROM payment_transaction WHERE user_id IN (?, ?)", userA, userB);
        terms.clear();
    }

    @Test
    void aMemberListsTheirPurchasesNewestFirstEachWithItsSourceAndNoText() {
        UUID r1 = terms.publish(1);
        UUID topUp = open(userA, "topup");
        markPaid(topUp);
        UUID plan = open(userA, "plan_purchase");
        jdbc.update("UPDATE payment_transaction SET terms_revision_id = NULL, created_at = now() - interval '30 days'"
                + " WHERE id = ?", plan);

        Page page = reader.page(userA, FIRST_PAGE);

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.rows()).extracting(row -> row.purchase().transactionId()).containsExactly(topUp, plan);
        AgreementRow first = page.rows().get(0);
        assertThat(first.terms().source()).isEqualTo("recorded");
        assertThat(first.terms().revisionId()).isEqualTo(r1);
        assertThat(first.terms().slug()).isEqualTo("legal-payment");
        assertThat(first.purchase().collectedAt()).isNotNull();
        assertThat(first.purchase().amountVnd()).isEqualTo(50_000L);
        assertThat(first.purchase().currency()).isEqualTo("VND");
        AgreementRow second = page.rows().get(1);
        assertThat(second.terms().source()).isEqualTo("no_record");
        assertThat(second.terms().revisionId()).isNull();
        assertThat(second.purchase().collectedAt()).isNull();
    }

    @Test
    void anotherMembersPurchasesNeverAppearAndTheirAgreementIsNotFound() {
        terms.publish(1);
        UUID mine = open(userA, "topup");
        UUID theirs = open(userB, "topup");

        Page page = reader.page(userA, FIRST_PAGE);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.rows()).extracting(row -> row.purchase().transactionId()).containsExactly(mine);
        assertThat(reader.find(userA, theirs)).isEmpty();
        assertThat(reader.find(userB, theirs)).isPresent();
    }

    @Test
    void aPinnedPurchaseReadsAsRecordedWithThatPublicationsText() {
        UUID r1 = terms.publish(1);
        UUID purchase = open(userA, "topup");
        terms.publish(2);

        Optional<Agreement> found = reader.find(userA, purchase);

        assertThat(found).isPresent();
        Agreement agreement = found.get();
        assertThat(agreement.terms().source()).isEqualTo("recorded");
        assertThat(agreement.terms().revisionId()).isEqualTo(r1);
        assertThat(agreement.sections().toString()).contains("Nội dung 1").doesNotContain("Nội dung 2");
        assertThat(agreement.purchase().transactionId()).isEqualTo(purchase);
    }

    @Test
    void aPurchaseWithNoPinTakesThePublicationInForceWhenItWasOpened() {
        UUID r1 = terms.publish(1);
        UUID purchase = open(userA, "topup");
        jdbc.update("UPDATE payment_transaction SET terms_revision_id = NULL WHERE id = ?", purchase);
        terms.publish(2);

        Agreement agreement = reader.find(userA, purchase).orElseThrow();

        assertThat(agreement.terms().source()).isEqualTo("reconstructed");
        assertThat(agreement.terms().revisionId()).isEqualTo(r1);
    }

    @Test
    void withNeitherAPublicationNorALiveCopyTheTermsAreUnavailable() {
        UUID purchase = open(userA, "topup");

        assertThatThrownBy(() -> reader.find(userA, purchase)).isInstanceOf(TermsUnavailableException.class);
        assertThatThrownBy(() -> reader.page(userA, FIRST_PAGE)).isInstanceOf(TermsUnavailableException.class);
    }

    @Test
    void aMemberWithNoPurchasesGetsAnEmptyPageWithoutReadingTheTerms() {
        Page page = reader.page(userA, FIRST_PAGE);

        assertThat(page.rows()).isEmpty();
        assertThat(page.total()).isZero();
    }

    private UUID open(UUID user, String kind) {
        UUID id = ledger.open(new OpenPayment(user, kind, "pkg-50k", "Payment", 50_000L, "momo", "MOMOBKUN20180529",
                Optional.of(PaymentInstrument.WALLET))).transactionId();
        return id;
    }

    private void markPaid(UUID transactionId) {
        jdbc.update("""
                UPDATE payment_transaction SET status = 'succeeded', succeeded_at = now(), base_amount_vnd = 45455,
                    tax_amount_vnd = 4545, tax_rate_bps = 1000, tax_inclusive = true,
                    tax_rate_id = (SELECT id FROM vat_rate WHERE valid_from = DATE '1900-01-01')
                WHERE id = ?""", transactionId);
    }
}
