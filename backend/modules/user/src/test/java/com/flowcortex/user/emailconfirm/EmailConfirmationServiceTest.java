package com.flowcortex.user.emailconfirm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.flowcortex.shared.web.LocaleConfig;
import com.flowcortex.shared.web.RequestLocale;
import com.flowcortex.user.infrastructure.AppUserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class EmailConfirmationServiceTest {

    private static final AnnotationConfigApplicationContext LOCALE =
            new AnnotationConfigApplicationContext(LocaleConfig.class);
    private static final Pattern TOKEN = Pattern.compile("confirm-email\\?token=([A-Za-z0-9_-]+)");

    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final EmailConfirmationService service = new EmailConfirmationService(
            mock(EmailConfirmationTokenRepository.class), mock(AppUserRepository.class), mailSender,
            new EmailConfirmationProperties(true, Duration.ofHours(24), "no-reply@flow-cortex.dev"),
            Clock.fixed(Instant.parse("2026-10-18T08:00:00Z"), ZoneOffset.UTC), "https://app.example/",
            LOCALE, LOCALE.getBean(RequestLocale.class));

    @AfterEach
    void unbind() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void withoutAnAcceptLanguageTheSubjectAndBodyAreTodaysEnglish() {
        SimpleMailMessage mail = issueAndCommit();

        assertThat(mail.getSubject()).isEqualTo("Confirm your Flow-Cortex email");
        String link = "https://app.example/confirm-email?token=" + tokenIn(mail.getText());
        assertThat(mail.getText()).isEqualTo("Hello,\n\n"
                + "Confirm your email to finish creating your Flow-Cortex account:\n\n"
                + link + "\n\n"
                + "The link works for 24 hours and only once. "
                + "If you did not create an account, you can ignore this email.\n");
    }

    @Test
    void theLanguageOfTheRegistrationRequestOutlivesTheRequestUntilTheMailLeaves() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.ACCEPT_LANGUAGE, "vi-VN");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        SimpleMailMessage mail = issueAndCommit();

        assertThat(mail.getSubject()).isEqualTo("Xác nhận email Flow-Cortex của bạn");
        assertThat(mail.getText()).startsWith("Xin chào,").contains("24 giờ")
                .contains("https://app.example/confirm-email?token=" + tokenIn(mail.getText()));
    }

    /** Unbinds the request before the commit, so only a language captured at issue time can reach the mail. */
    private SimpleMailMessage issueAndCommit() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.issue(UUID.randomUUID(), "member@example.com");
            RequestContextHolder.resetRequestAttributes();
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(sent.capture());
        return sent.getValue();
    }

    private static String tokenIn(String body) {
        Matcher matcher = TOKEN.matcher(body);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }
}
