package com.flowcortex.app.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;

import com.flowcortex.app.workflow.AbstractWorkflowModuleIT;
import com.flowcortex.billing.receipt.ReceiptMail;
import com.flowcortex.mail.api.MailAttachment;
import com.flowcortex.mail.api.MailKey;
import com.flowcortex.mail.api.OutgoingMail;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.user.api.AccountPreferences;
import com.flowcortex.user.api.UserView;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest
class ReceiptMailIT extends AbstractWorkflowModuleIT {

    private static final Instant PAID_AT = Instant.parse("2026-12-31T17:05:00Z");
    private static final String PUBLIC_URL = "https://app.nodi.vn";

    @Autowired
    private ReceiptMail receiptMail;

    @MockitoSpyBean
    private AccountPreferences preferences;

    @Autowired
    private JdbcTemplate jdbc;

    private UserView member;
    private UUID goneMember;
    private UUID transactionId;
    private String orderRef;

    @BeforeEach
    void payment() {
        member = newUser("receipt-mail");
        transactionId = ReceiptFixtures.collected(jdbc, member.id(), "Nạp 1.000 credit", 100_000, 90_909, 9_091,
                PAID_AT);
        orderRef = ReceiptFixtures.orderRef(jdbc, transactionId);
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM payment_transaction WHERE user_id = ?", member.id());
        if (goneMember != null) {
            jdbc.update("DELETE FROM payment_transaction WHERE user_id = ?", goneMember);
        }
    }

    @Test
    void anEnglishMemberGetsTheReceiptInEnglishWithSixTextValuesAndThePdfAttached() {
        preferences.setLocale(member.id(), "en");

        OutgoingMail mail = receiptMail.build(member.id(), transactionId, PUBLIC_URL).orElseThrow();

        assertThat(mail.key()).isEqualTo(MailKey.PAYMENT_RECEIPT);
        assertThat(mail.memberId()).isEqualTo(member.id());
        assertThat(mail.to()).isEqualTo(member.email());
        assertThat(mail.locale()).isEqualTo(Locale.ENGLISH);
        assertThat(mail.values()).containsExactly(member.name(), orderRef, "01/01/2027 00:05 (GMT+7)", "100.000 ₫",
                "Nạp 1.000 credit", PUBLIC_URL + "/billing/transactions/" + transactionId + "/receipt");
        assertThat(mail.attachments()).singleElement().satisfies(attachment -> {
            assertThat(attachment.fileName()).isEqualTo("bien-nhan-" + orderRef + ".pdf");
            assertThat(attachment.contentType()).isEqualTo("application/pdf");
            assertThat(new String(attachment.content(), 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
        });
    }

    @Test
    void aMemberWithNoSavedLanguageGetsVietnamese() {
        OutgoingMail mail = receiptMail.build(member.id(), transactionId, PUBLIC_URL).orElseThrow();

        assertThat(mail.locale()).isEqualTo(Locale.forLanguageTag("vi"));
    }

    @Test
    void aMemberWhosePreferencesCannotBeReadGetsVietnamese() {
        doThrow(new AccessDeniedException("suspended")).when(preferences).get(member.id());

        OutgoingMail mail = receiptMail.build(member.id(), transactionId, PUBLIC_URL).orElseThrow();

        assertThat(mail.locale()).isEqualTo(Locale.forLanguageTag("vi"));
    }

    @Test
    void aMemberWithoutANameIsGreetedWithTheFallbackWord() {
        jdbc.update("UPDATE app_user SET name = '' WHERE id = ?", member.id());

        OutgoingMail mail = receiptMail.build(member.id(), transactionId, PUBLIC_URL).orElseThrow();

        assertThat(mail.values().getFirst()).isInstanceOfSatisfying(LocalizedMessage.class,
                greeting -> assertThat(greeting.code()).isEqualTo("billing.receipt.mail.greetingFallback"));
    }

    @Test
    void aTrailingSlashOnThePublicUrlLeavesOneSlashInTheLink() {
        OutgoingMail mail = receiptMail.build(member.id(), transactionId, PUBLIC_URL + "/").orElseThrow();

        assertThat(mail.values().get(5)).isEqualTo(PUBLIC_URL + "/billing/transactions/" + transactionId + "/receipt");
    }

    @Test
    void aMemberWhoseRowIsGoneHasNoMail() {
        goneMember = UUID.randomUUID();
        UUID orphan = ReceiptFixtures.collected(jdbc, goneMember, "Nạp 500 credit", 50_000, 45_455, 4_545, PAID_AT);

        Optional<OutgoingMail> mail = receiptMail.build(goneMember, orphan, PUBLIC_URL);

        assertThat(mail).isEmpty();
    }

    @Test
    void theAttachmentIsTheLanguagesPdf() {
        preferences.setLocale(member.id(), "en");
        MailAttachment english = receiptMail.build(member.id(), transactionId, PUBLIC_URL).orElseThrow()
                .attachments().getFirst();
        preferences.setLocale(member.id(), "vi");
        MailAttachment vietnamese = receiptMail.build(member.id(), transactionId, PUBLIC_URL).orElseThrow()
                .attachments().getFirst();

        assertThat(english.content()).isNotEqualTo(vietnamese.content());
        assertThat(List.of(english, vietnamese)).extracting(MailAttachment::fileName).containsOnly(
                "bien-nhan-" + orderRef + ".pdf");
    }
}
