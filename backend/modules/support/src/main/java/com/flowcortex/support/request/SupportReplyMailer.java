package com.flowcortex.support.request;

import com.flowcortex.mail.api.MailKey;
import com.flowcortex.mail.api.MailOutcome;
import com.flowcortex.mail.api.Mailer;
import com.flowcortex.mail.api.OutgoingMail;
import com.flowcortex.support.config.SupportProperties;
import com.flowcortex.user.api.UserDirectory;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Mails the member a link to each admin reply once the reply is committed. A failed send is rethrown so the
 * event registry keeps the publication as failed and retries it by {@link SupportReplyMailRetryPolicy}; a mail
 * the member opted out of is not retried. The reply itself is already saved. Logs carry the request number,
 * never the address.
 */
@Component
class SupportReplyMailer {

    /** A request filed in any language other than English is mailed in Vietnamese. */
    private static final Locale VIETNAMESE = Locale.of("vi");
    private static final Logger LOG = LoggerFactory.getLogger(SupportReplyMailer.class);

    private final UserDirectory users;
    private final Mailer mailer;
    private final String publicUrl;

    SupportReplyMailer(UserDirectory users, Mailer mailer, SupportProperties properties,
            @Value("${flowcortex.app.public-url:}") String publicUrl) {
        if (properties.enabled() && (publicUrl == null || publicUrl.isBlank())) {
            throw new IllegalStateException("flowcortex.support.enabled is true but flowcortex.app.public-url "
                    + "(FLOWCORTEX_APP_PUBLIC_URL), the member app origin the reply mail links to, is not set");
        }
        this.users = users;
        this.mailer = mailer;
        this.publicUrl = publicUrl == null ? "" : publicUrl.endsWith("/")
                ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
    }

    @ApplicationModuleListener(id = "support.reply-mail")
    void on(SupportReplyMailRequested event) {
        String email = users.findEmailsByIds(List.of(event.recipientUserId())).get(event.recipientUserId());
        if (email == null) {
            LOG.info("support.reply.mail outcome=recipient_deleted request={}", event.requestNumber());
            return;
        }
        MailOutcome outcome;
        try {
            outcome = mailer.send(mail(email, event));
        } catch (RuntimeException e) {
            // Only the class name: an exception can carry the message and its recipient.
            LOG.warn("support.reply.mail outcome=failed request={} error={}", event.requestNumber(),
                    e.getClass().getSimpleName());
            throw e;
        }
        if (outcome == MailOutcome.FAILED) {
            LOG.warn("support.reply.mail outcome=failed request={} error=send_failed", event.requestNumber());
            throw new IllegalStateException("The reply mail for request " + event.requestNumber() + " was not sent");
        }
        LOG.info("support.reply.mail outcome={} request={}", outcome == MailOutcome.SENT ? "sent" : "suppressed",
                event.requestNumber());
    }

    private OutgoingMail mail(String email, SupportReplyMailRequested event) {
        Locale locale = "en".equals(event.locale()) ? Locale.ENGLISH : VIETNAMESE;
        long number = event.requestNumber();
        return new OutgoingMail(MailKey.SUPPORT_REQUEST_REPLIED, event.recipientUserId(), email, locale,
                List.of(number, publicUrl + "/?help=request-" + number));
    }
}
