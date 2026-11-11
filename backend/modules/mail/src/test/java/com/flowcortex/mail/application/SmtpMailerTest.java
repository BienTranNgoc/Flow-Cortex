package com.flowcortex.mail.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.flowcortex.mail.api.MailCategory;
import com.flowcortex.mail.api.MailKey;
import com.flowcortex.mail.api.MailOutcome;
import com.flowcortex.mail.api.OutgoingMail;
import com.flowcortex.mail.links.LinkList;
import com.flowcortex.mail.links.LinkState;
import com.flowcortex.mail.links.UnsubscribeLink;
import com.flowcortex.mail.links.UnsubscribeLinks;
import com.flowcortex.shared.web.LocalizedMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

class SmtpMailerTest {

    private static final Locale VIETNAMESE = Locale.of("vi");
    private static final String LINK = "https://app.example/reset-password?token=secret-token";
    private static final String ADDRESS = "alice@example.com";

    private final JavaMailSender transport = mock(JavaMailSender.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<JavaMailSender> transportProvider = mock(ObjectProvider.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger mailerLog = (Logger) LoggerFactory.getLogger(SmtpMailer.class);
    private final UUID memberId = UUID.randomUUID();
    private final UnsubscribeLinks links = mock(UnsubscribeLinks.class);
    private SmtpMailer mailer;

    @BeforeEach
    void setUp() {
        when(transportProvider.getIfAvailable()).thenReturn(transport);
        when(transport.createMimeMessage()).thenAnswer(call -> new MimeMessage((Session) null));
        mailer = new SmtpMailer(messageSource(), transportProvider,
                new MailProperties("no-reply@flow-cortex.dev", "Nodi", "", null), meters, links);
        logs.start();
        mailerLog.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        mailerLog.detachAppender(logs);
    }

    @Test
    void aWrongValueCountIsRefusedBeforeTheTransport() {
        OutgoingMail oneValueShort = mail(MailKey.RESET_PASSWORD, Locale.ENGLISH, List.of(LINK));

        assertThatThrownBy(() -> mailer.send(oneValueShort)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reset-password");
        verify(transport, never()).send(any(MimeMessage.class));
        verify(transport, never()).createMimeMessage();
    }

    @Test
    void aMissingAddressOrMemberIsRefused() {
        List<Object> values = List.of(LINK, new LocalizedMessage("email.duration.hours", 1));

        assertThatThrownBy(() -> mailer.send(new OutgoingMail(MailKey.RESET_PASSWORD, memberId, " ", Locale.ENGLISH,
                values))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mailer.send(new OutgoingMail(MailKey.RESET_PASSWORD, null, ADDRESS, Locale.ENGLISH,
                values))).isInstanceOf(IllegalArgumentException.class);
        verify(transport, never()).send(any(MimeMessage.class));
    }

    @Test
    void aTransportExceptionIsFailedAndLogsOnlyItsClass() {
        doThrow(new MailSendException("relay refused " + LINK)).when(transport).send(any(MimeMessage.class));

        MailOutcome outcome = mailer.send(resetMail(Locale.ENGLISH));

        assertThat(outcome).isEqualTo(MailOutcome.FAILED);
        assertThat(logLines()).anySatisfy(line -> assertThat(line).contains("key=reset-password")
                .contains("outcome=failed").contains(memberId.toString()).contains(MailSendException.class.getName()));
        assertThat(String.join("\n", logLines())).doesNotContain(LINK).doesNotContain("secret-token")
                .doesNotContain(ADDRESS);
    }

    @Test
    void noTransportBeanIsFailed() {
        when(transportProvider.getIfAvailable()).thenReturn(null);

        assertThat(mailer.send(resetMail(Locale.ENGLISH))).isEqualTo(MailOutcome.FAILED);
        assertThat(count("reset-password", "failed")).isEqualTo(1);
    }

    @Test
    void aValueOfTheWrongTypeIsFailedAndLogsOnlyItsClass() {
        OutgoingMail mail = mail(MailKey.SUPPORT_REQUEST_REPLIED, Locale.ENGLISH, List.of("not-a-number-value", LINK));

        assertThat(mailer.send(mail)).isEqualTo(MailOutcome.FAILED);
        verify(transport, never()).send(any(MimeMessage.class));
        assertThat(logLines()).anySatisfy(line -> assertThat(line).contains("outcome=failed")
                .contains(IllegalArgumentException.class.getName()));
        assertThat(String.join("\n", logLines())).doesNotContain("not-a-number-value");
    }

    @Test
    void aSentMailIsMultipartFromTheConfiguredSender() throws Exception {
        MailOutcome outcome = mailer.send(resetMail(VIETNAMESE));

        assertThat(outcome).isEqualTo(MailOutcome.SENT);
        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(transport).send(sent.capture());
        MimeMessage message = sent.getValue();
        // The transport does this before writing; without it the parts carry no content type yet.
        message.saveChanges();
        assertThat(message.getFrom()[0].toString()).isEqualTo("Nodi <no-reply@flow-cortex.dev>");
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo(ADDRESS);
        assertThat(message.getSubject()).isEqualTo("Đặt lại mật khẩu Flow-Cortex của bạn");

        List<String> plain = new ArrayList<>();
        List<String> html = new ArrayList<>();
        collect((Multipart) message.getContent(), plain, html);
        assertThat(plain).hasSize(1);
        assertThat(plain.getFirst()).startsWith("Xin chào,").contains(LINK).contains("1 giờ");
        assertThat(html).hasSize(1);
        assertThat(html.getFirst()).contains("lang=\"vi\"").contains("href=\"" + LINK + "\"");
    }

    @Test
    void eachSendIsCountedByKeyAndOutcome() {
        mailer.send(resetMail(Locale.ENGLISH));
        mailer.send(resetMail(Locale.ENGLISH));
        doThrow(new MailSendException("down")).when(transport).send(any(MimeMessage.class));
        mailer.send(resetMail(Locale.ENGLISH));

        assertThat(count("reset-password", "sent")).isEqualTo(2);
        assertThat(count("reset-password", "failed")).isEqualTo(1);
        assertThat(logLines()).anySatisfy(line -> assertThat(line).contains("mail.send key=reset-password")
                .contains("outcome=sent").contains(memberId.toString()));
    }

    @Test
    void aCareMailToACareUnsubscribedMemberIsSuppressed() {
        assertThat(SendPolicy.decide(MailCategory.CARE, new LinkState(true, false)))
                .isEqualTo(SendPolicy.Verdict.SUPPRESS);
        assertThat(SendPolicy.decide(MailCategory.CARE, new LinkState(false, false)))
                .isEqualTo(SendPolicy.Verdict.SEND);
    }

    @Test
    void aPromotionalMailToAMarketingUnsubscribedMemberIsSuppressed() {
        assertThat(SendPolicy.decide(MailCategory.PROMOTIONAL, new LinkState(false, true)))
                .isEqualTo(SendPolicy.Verdict.SUPPRESS);
        assertThat(SendPolicy.decide(MailCategory.PROMOTIONAL, new LinkState(false, false)))
                .isEqualTo(SendPolicy.Verdict.SEND);
    }

    @Test
    void aCareUnsubscribeDoesNotSuppressPromotionalOrTheReverse() {
        assertThat(SendPolicy.decide(MailCategory.PROMOTIONAL, new LinkState(true, false)))
                .isEqualTo(SendPolicy.Verdict.SEND);
        assertThat(SendPolicy.decide(MailCategory.CARE, new LinkState(false, true)))
                .isEqualTo(SendPolicy.Verdict.SEND);
    }

    @Test
    void aTransactionalMailIsNeverSuppressed() {
        assertThat(SendPolicy.decide(MailCategory.TRANSACTIONAL, new LinkState(true, true)))
                .isEqualTo(SendPolicy.Verdict.SEND);
    }

    @Test
    void aTransactionalSendNeverReadsTheLinkStore() {
        assertThat(mailer.send(resetMail(Locale.ENGLISH))).isEqualTo(MailOutcome.SENT);

        verifyNoInteractions(links);
    }

    @Test
    void aCareMailCarriesTheUnsubscribeLinkInBothPartsAndTheHeaders() {
        UnsubscribeLink link = careLink();

        MailFooter footer = MailFooter.of(MailCategory.CARE, link, "Unsubscribe");

        assertThat(footer.html()).contains("<a href=\"https://app.example/email/unsubscribe/tok?src=reset-password\"")
                .contains(">Unsubscribe</a>");
        assertThat(footer.textSuffix()).endsWith("Unsubscribe: " + link.footerUrl());
        assertThat(footer.headers()).containsEntry("List-Unsubscribe", "<" + link.oneClickUrl() + ">")
                .containsEntry("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
        assertThat(MailFooter.of(MailCategory.TRANSACTIONAL, null, "Unsubscribe")).isEqualTo(MailFooter.NONE);
        assertThat(MailFooter.NONE.html()).isEmpty();
        assertThat(MailFooter.NONE.textSuffix()).isEmpty();
        assertThat(MailFooter.NONE.headers()).isEmpty();
    }

    @Test
    void aCareMailGoesOutThroughTheMailerWithTheFooterAndHeaders() throws Exception {
        UnsubscribeLink link = careLink();
        when(links.state(memberId)).thenReturn(new LinkState(false, false));
        when(links.forSend(memberId, LinkList.CARE, MailKey.RESET_PASSWORD)).thenReturn(link);

        assertThat(mailer.deliver(resetMail(Locale.ENGLISH), MailCategory.CARE)).isEqualTo(MailOutcome.SENT);

        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(transport).send(sent.capture());
        MimeMessage message = sent.getValue();
        message.saveChanges();
        assertThat(message.getHeader("List-Unsubscribe")).containsExactly("<" + link.oneClickUrl() + ">");
        assertThat(message.getHeader("List-Unsubscribe-Post")).containsExactly("List-Unsubscribe=One-Click");
        List<String> plain = new ArrayList<>();
        List<String> html = new ArrayList<>();
        collect((Multipart) message.getContent(), plain, html);
        assertThat(plain.getFirst()).endsWith("Unsubscribe: " + link.footerUrl());
        assertThat(html.getFirst()).contains("href=\"" + link.footerUrl() + "\"");
    }

    @Test
    void aSuppressedCareMailNeverReachesTheTransportAndIsCounted() {
        when(links.state(memberId)).thenReturn(new LinkState(true, false));

        assertThat(mailer.deliver(resetMail(Locale.ENGLISH), MailCategory.CARE)).isEqualTo(MailOutcome.SUPPRESSED);

        verify(transport, never()).send(any(MimeMessage.class));
        verify(links, never()).forSend(any(), any(), any());
        assertThat(count("reset-password", "suppressed")).isEqualTo(1);
    }

    @Test
    void aFailingLinkStoreFailsACareMailWithoutLeakingItsMessage() {
        when(links.state(memberId)).thenThrow(new IllegalStateException("connection to db at " + ADDRESS));

        assertThat(mailer.deliver(resetMail(Locale.ENGLISH), MailCategory.CARE)).isEqualTo(MailOutcome.FAILED);

        verify(transport, never()).send(any(MimeMessage.class));
        assertThat(String.join("\n", logLines())).doesNotContain(ADDRESS);
    }

    @Test
    void aTransactionalTextPartIsTheRenderedBodyUnchanged() throws Exception {
        mailer.send(resetMail(Locale.ENGLISH));

        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(transport).send(sent.capture());
        sent.getValue().saveChanges();
        List<String> plain = new ArrayList<>();
        collect((Multipart) sent.getValue().getContent(), plain, new ArrayList<>());
        String body = messageSource().getMessage("email.passwordReset.body", new Object[] {LINK, "1 hour"},
                Locale.ENGLISH);
        assertThat(plain.getFirst()).isEqualTo(body);
        assertThat(sent.getValue().getHeader("List-Unsubscribe")).isNull();
    }

    private static UnsubscribeLink careLink() {
        return new UnsubscribeLink("https://app.example/email/unsubscribe/tok?src=reset-password",
                "https://app.example/api/v1/email-links/tok/one-click?src=reset-password");
    }

    private OutgoingMail resetMail(Locale locale) {
        return mail(MailKey.RESET_PASSWORD, locale, List.of(LINK, new LocalizedMessage("email.duration.hours", 1)));
    }

    private OutgoingMail mail(MailKey key, Locale locale, List<Object> values) {
        return new OutgoingMail(key, memberId, ADDRESS, locale, values);
    }

    private double count(String key, String outcome) {
        return meters.counter("mail.sent", "key", key, "outcome", outcome).count();
    }

    private List<String> logLines() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static void collect(Multipart multipart, List<String> plain, List<String> html) throws Exception {
        for (int index = 0; index < multipart.getCount(); index++) {
            BodyPart part = multipart.getBodyPart(index);
            if (part.getContent() instanceof Multipart nested) {
                collect(nested, plain, html);
            } else if (part.isMimeType("text/plain")) {
                plain.add((String) part.getContent());
            } else if (part.isMimeType("text/html")) {
                html.add((String) part.getContent());
            }
        }
    }

    private static ResourceBundleMessageSource messageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("i18n/messages");
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        source.setFallbackToSystemLocale(false);
        return source;
    }
}
