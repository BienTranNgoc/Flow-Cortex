package com.flowcortex.mail.application;

import com.flowcortex.mail.api.MailCategory;
import com.flowcortex.mail.api.MailKey;
import com.flowcortex.mail.api.MailOutcome;
import com.flowcortex.mail.api.Mailer;
import com.flowcortex.mail.api.OutgoingMail;
import com.flowcortex.mail.application.SendPolicy.Verdict;
import com.flowcortex.mail.links.LinkList;
import com.flowcortex.mail.links.UnsubscribeLink;
import com.flowcortex.mail.links.UnsubscribeLinks;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Renders a catalog key as text plus HTML from the message bundle and hands it to the SMTP relay.
 * The decision order is fixed: arguments, list state (care and promotional only), render with the
 * unsubscribe footer (same), transport present, transport.
 */
@Component
class SmtpMailer implements Mailer {

    private static final Logger LOG = LoggerFactory.getLogger(SmtpMailer.class);

    private final MessageSource messages;
    private final ObjectProvider<JavaMailSender> transport;
    private final MailProperties properties;
    private final MeterRegistry meters;
    private final UnsubscribeLinks links;

    SmtpMailer(MessageSource messages, ObjectProvider<JavaMailSender> transport, MailProperties properties,
            MeterRegistry meters, UnsubscribeLinks links) {
        this.messages = messages;
        this.transport = transport;
        this.properties = properties;
        this.meters = meters;
        this.links = links;
    }

    @Override
    public MailOutcome send(OutgoingMail mail) {
        return deliver(mail, mail == null || mail.key() == null ? null : mail.key().category());
    }

    /**
     * The send pipeline with the category as a parameter, so the care and promotional paths can be driven
     * before the catalog holds a key of those categories.
     */
    MailOutcome deliver(OutgoingMail mail, MailCategory category) {
        requireWellFormed(mail);
        try {
            Optional<LinkList> list = LinkList.of(category);
            if (list.isPresent() && SendPolicy.decide(category, links.state(mail.memberId())) == Verdict.SUPPRESS) {
                return record(mail, MailOutcome.SUPPRESSED, null);
            }
            UnsubscribeLink link = list.map(target -> links.forSend(mail.memberId(), target, mail.key()))
                    .orElse(null);
            Rendered rendered = render(mail, MailFooter.of(category, link,
                    messages.getMessage("mail.footer.unsubscribe", null, mail.locale())));
            JavaMailSender sender = transport.getIfAvailable();
            if (sender == null) {
                return record(mail, MailOutcome.FAILED, null);
            }
            sender.send(mimeMessage(sender, mail, rendered));
        } catch (RuntimeException | UnsupportedEncodingException | MessagingException ex) {
            return record(mail, MailOutcome.FAILED, ex);
        }
        return record(mail, MailOutcome.SENT, null);
    }

    /** Counts and logs the outcome; the log carries the exception class only, never its message. */
    private MailOutcome record(OutgoingMail mail, MailOutcome outcome, Exception cause) {
        String name = outcome.name().toLowerCase(Locale.ROOT);
        meters.counter("mail.sent", "key", mail.key().id(), "outcome", name).increment();
        if (cause == null) {
            LOG.info("mail.send key={} outcome={} memberId={}", mail.key().id(), name, mail.memberId());
        } else {
            LOG.warn("mail.send key={} outcome={} memberId={} error={}", mail.key().id(), name, mail.memberId(),
                    cause.getClass().getName());
        }
        return outcome;
    }

    private static void requireWellFormed(OutgoingMail mail) {
        if (mail == null || mail.key() == null) {
            throw new IllegalArgumentException("A mail must name its catalog key");
        }
        MailKey key = mail.key();
        if (mail.memberId() == null) {
            throw new IllegalArgumentException("Mail " + key.id() + " names no recipient member");
        }
        if (mail.to() == null || mail.to().isBlank()) {
            throw new IllegalArgumentException("Mail " + key.id() + " names no recipient address");
        }
        if (mail.locale() == null) {
            throw new IllegalArgumentException("Mail " + key.id() + " names no language");
        }
        if (mail.values().size() != key.valueCount()) {
            throw new IllegalArgumentException("Mail " + key.id() + " takes " + key.valueCount() + " values, got "
                    + mail.values().size());
        }
    }

    private Rendered render(OutgoingMail mail, MailFooter unsubscribe) {
        Object[] values = mail.values().toArray();
        String subject = messages.getMessage(mail.key().subjectCode(), values, mail.locale());
        String body = messages.getMessage(mail.key().bodyCode(), values, mail.locale());
        String sentBy = MailHtml.escape(messages.getMessage("mail.footer.sentBy",
                new Object[] {properties.fromName()}, mail.locale()));
        String footer = unsubscribe.html().isEmpty() ? sentBy : sentBy + "<br>" + unsubscribe.html();
        String html = MailHtml.page(mail.locale().getLanguage(), properties.fromName(), MailHtml.paragraphs(body),
                footer);
        return new Rendered(subject, body + unsubscribe.textSuffix(), html, unsubscribe.headers());
    }

    private MimeMessage mimeMessage(JavaMailSender sender, OutgoingMail mail, Rendered rendered)
            throws MessagingException, UnsupportedEncodingException {
        MimeMessage message = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
        helper.setFrom(new InternetAddress(properties.from(), properties.fromName(), StandardCharsets.UTF_8.name()));
        helper.setTo(mail.to());
        helper.setSubject(rendered.subject());
        helper.setText(rendered.text(), rendered.html());
        for (Map.Entry<String, String> header : rendered.headers().entrySet()) {
            message.addHeader(header.getKey(), header.getValue());
        }
        return message;
    }

    private record Rendered(String subject, String text, String html, Map<String, String> headers) {
    }
}
