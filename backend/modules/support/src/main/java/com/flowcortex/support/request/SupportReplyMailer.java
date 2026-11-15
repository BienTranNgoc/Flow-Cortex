package com.flowcortex.support.request;

import com.flowcortex.support.config.SupportProperties;
import com.flowcortex.user.api.UserDirectory;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Mails the member a link to each admin reply once the reply is committed. A failed send is rethrown so the
 * event registry keeps the publication as failed and retries it by {@link SupportReplyMailRetryPolicy}; the
 * reply itself is already saved. Logs carry the request number, never the address.
 */
@Component
class SupportReplyMailer {

    /** A request filed in any language other than English is mailed in Vietnamese. */
    private static final Locale VIETNAMESE = Locale.of("vi");
    private static final Logger LOG = LoggerFactory.getLogger(SupportReplyMailer.class);

    private final UserDirectory users;
    private final ObjectProvider<JavaMailSender> mailSender;
    private final SupportProperties properties;
    private final MessageSource messages;
    private final String publicUrl;

    SupportReplyMailer(UserDirectory users, ObjectProvider<JavaMailSender> mailSender, SupportProperties properties,
            MessageSource messages, @Value("${flowcortex.app.public-url:}") String publicUrl) {
        if (properties.enabled() && (publicUrl == null || publicUrl.isBlank())) {
            throw new IllegalStateException("flowcortex.support.enabled is true but flowcortex.app.public-url "
                    + "(FLOWCORTEX_APP_PUBLIC_URL), the member app origin the reply mail links to, is not set");
        }
        this.users = users;
        this.mailSender = mailSender;
        this.properties = properties;
        this.messages = messages;
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
        try {
            JavaMailSender sender = mailSender.getIfAvailable();
            if (sender == null) {
                throw new IllegalStateException("No mail sender is configured");
            }
            sender.send(message(email, event));
        } catch (RuntimeException e) {
            // Only the class name: a send exception can carry the message and its recipient.
            LOG.warn("support.reply.mail outcome=failed request={} error={}", event.requestNumber(),
                    e.getClass().getSimpleName());
            throw e;
        }
        LOG.info("support.reply.mail outcome=sent request={}", event.requestNumber());
    }

    private SimpleMailMessage message(String email, SupportReplyMailRequested event) {
        Locale locale = "en".equals(event.locale()) ? Locale.ENGLISH : VIETNAMESE;
        long number = event.requestNumber();
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.mail().from());
        message.setTo(email);
        message.setSubject(messages.getMessage("support.mail.subject", new Object[] {number}, locale));
        message.setText(messages.getMessage("support.mail.body",
                new Object[] {number, publicUrl + "/?help=request-" + number}, locale));
        return message;
    }
}
