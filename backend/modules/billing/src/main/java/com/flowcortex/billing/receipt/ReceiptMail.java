package com.flowcortex.billing.receipt;

import com.flowcortex.mail.api.MailAttachment;
import com.flowcortex.mail.api.MailKey;
import com.flowcortex.mail.api.OutgoingMail;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.user.api.AccountPreferences;
import com.flowcortex.user.api.UserDirectory;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

/** Builds the receipt mail of a collected payment: the member's address and language, the values, the PDF. */
@Component
public class ReceiptMail {

    private static final Locale VIETNAMESE = Locale.of("vi");

    private final ReceiptReader reader;
    private final MessageSource messages;
    private final UserDirectory users;
    private final AccountPreferences preferences;

    ReceiptMail(ReceiptReader reader, MessageSource messages, UserDirectory users, AccountPreferences preferences) {
        this.reader = reader;
        this.messages = messages;
        this.users = users;
        this.preferences = preferences;
    }

    /**
     * The mail for {@code member}'s payment, empty when the member has no address any more. The language is the
     * member's saved one, Vietnamese when none is saved or it cannot be read. The paid time and the amount are
     * text as the PDF prints them, so no locale formats them again in the message.
     *
     * @param publicUrl the member app origin the receipt link starts with; a trailing slash is dropped
     */
    public Optional<OutgoingMail> build(UUID member, UUID transactionId, String publicUrl) {
        String address = users.findEmailsByIds(List.of(member)).get(member);
        if (address == null) {
            return Optional.empty();
        }
        Receipt receipt = reader.findOwn(member, transactionId).orElseThrow(
                () -> new IllegalStateException("A receipt delivery names a payment that is not the member's"));
        Locale locale = languageOf(member);
        String name = users.findNamesByIds(List.of(member)).get(member);
        List<Object> values = List.of(
                name == null || name.isBlank() ? new LocalizedMessage("billing.receipt.mail.greetingFallback") : name,
                receipt.orderRef(), ReceiptFormat.paidAt(receipt.paidAt()), ReceiptFormat.vnd(receipt.totalVnd()),
                receipt.lines().getFirst().description(), link(publicUrl, receipt));
        MailAttachment pdf = new MailAttachment(receipt.pdfFileName(), "application/pdf",
                ReceiptPdf.render(receipt, new ReceiptCopy(messages, locale)));
        return Optional.of(new OutgoingMail(MailKey.PAYMENT_RECEIPT, member, address, locale, values,
                List.of(pdf)));
    }

    private Locale languageOf(UUID member) {
        try {
            return "en".equals(preferences.get(member).locale()) ? Locale.ENGLISH : VIETNAMESE;
        } catch (RuntimeException e) {
            return VIETNAMESE;
        }
    }

    private static String link(String publicUrl, Receipt receipt) {
        String origin = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
        return origin + "/billing/transactions/" + receipt.transactionId() + "/receipt";
    }
}
