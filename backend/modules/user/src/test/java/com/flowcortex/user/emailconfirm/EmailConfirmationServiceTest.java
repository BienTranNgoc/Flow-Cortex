package com.flowcortex.user.emailconfirm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.shared.web.LocaleConfig;
import com.flowcortex.shared.web.RequestLocale;
import com.flowcortex.user.domain.AppUser;
import com.flowcortex.user.domain.Role;
import com.flowcortex.user.domain.UserStatus;
import com.flowcortex.user.infrastructure.AppUserRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpHeaders;
import org.springframework.mail.MailSendException;
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
    private static final Pattern TOKEN = Pattern.compile("(?:confirm-email|set-password)\\?token=([A-Za-z0-9_-]+)");
    private static final Instant NOW = Instant.parse("2026-10-18T09:00:00Z");

    private final EmailConfirmationTokenRepository tokens = mock(EmailConfirmationTokenRepository.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private TaskExecutor mailExecutor = Runnable::run;

    private EmailConfirmationService service(boolean enabled, String publicUrl, String mailHost) {
        return new EmailConfirmationService(tokens, users, mailSender, mailExecutor,
                new EmailConfirmationProperties(enabled, Duration.ofHours(24), "no-reply@flow-cortex.dev",
                        Duration.ofHours(72), Duration.ofMinutes(30), 500),
                Clock.fixed(NOW, ZoneOffset.UTC), publicUrl, mailHost, meters, LOCALE,
                LOCALE.getBean(RequestLocale.class));
    }

    @AfterEach
    void unbind() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void confirmationOnWithoutAPublicUrlOrAMailHostRefusesToStartNamingWhatIsMissing() {
        for (String blank : new String[] {"", "  ", null}) {
            assertThatThrownBy(() -> service(true, blank, "mailpit"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("flowcortex.app.public-url (FLOWCORTEX_APP_PUBLIC_URL)");
            assertThatThrownBy(() -> service(true, "https://app.example.com", blank))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("spring.mail.host (SPRING_MAIL_HOST)");
        }
    }

    @Test
    void confirmationOffStartsWithoutEither() {
        assertThat(service(false, "", "")).isNotNull();
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

    @Test
    void withoutAnAcceptLanguageTheSetPasswordLinkAndNoticeAreTodaysEnglish() {
        UUID userId = UUID.randomUUID();
        EmailConfirmationService service = service(true, "https://app.example.com/", "mailpit");

        runCommitting(() -> givenGoogleOnlyAccountResent(service));
        runCommitting(() -> service.mailPasswordSetNotice(userId, "an@example.com"));

        ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, org.mockito.Mockito.times(2)).send(mail.capture());
        SimpleMailMessage link = mail.getAllValues().get(0);
        assertThat(link.getSubject()).isEqualTo("Set your Flow-Cortex password");
        assertThat(link.getText()).isEqualTo("Hello,\n\n"
                + "Your Flow-Cortex account has no password yet. Open this link to sign in and choose one:\n\n"
                + "https://app.example.com/set-password?token=" + tokenIn(link.getText()) + "\n\n"
                + "The link works for 30 minutes and only once. "
                + "If you did not ask for it, you can ignore this email.\n");
        SimpleMailMessage notice = mail.getAllValues().get(1);
        assertThat(notice.getSubject()).isEqualTo("Your Flow-Cortex password was set");
        assertThat(notice.getText()).isEqualTo("Hello,\n\n"
                + "A password was just set for your Flow-Cortex account, and every other signed-in session"
                + " was ended.\n\n"
                + "If this was not you, contact support right away.\n");
    }

    @Test
    void aVietnameseRequestGetsTheSetPasswordLinkAndNoticeInVietnamese() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.ACCEPT_LANGUAGE, "vi");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        EmailConfirmationService service = service(true, "https://app.example.com", "mailpit");

        runCommitting(() -> givenGoogleOnlyAccountResent(service));
        runCommitting(() -> service.mailPasswordSetNotice(UUID.randomUUID(), "an@example.com"));

        ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, org.mockito.Mockito.times(2)).send(mail.capture());
        assertThat(mail.getAllValues().get(0).getSubject()).isEqualTo("Đặt mật khẩu Flow-Cortex của bạn");
        assertThat(mail.getAllValues().get(0).getText()).startsWith("Xin chào,").contains("30 phút");
        assertThat(mail.getAllValues().get(1).getSubject()).isEqualTo("Mật khẩu Flow-Cortex của bạn đã được đặt");
    }

    @Test
    void aMailTheServerRefusesIsCountedAndDoesNotFailTheCaller() {
        doThrow(new MailSendException("refused")).when(mailSender).send(any(SimpleMailMessage.class));
        EmailConfirmationService service = service(true, "https://app.example.com/", "mailpit");

        runCommitting(() -> service.issue(UUID.randomUUID(), "an@example.com"));

        assertThat(meters.get("email.confirmation.send_failed").counter().count()).isEqualTo(1.0);
    }

    @Test
    void mailIsHandedToTheExecutorOnlyAfterCommitAndAFullQueueIsCountedWithoutFailingTheCaller() {
        List<Runnable> queued = new ArrayList<>();
        mailExecutor = queued::add;
        EmailConfirmationService service = service(true, "https://app.example.com", "mailpit");

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.issue(UUID.randomUUID(), "an@example.com");
            assertThat(queued).as("nothing leaves before the commit").isEmpty();
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
        assertThat(queued).hasSize(1);
        queued.getFirst().run();
        verify(mailSender).send(any(SimpleMailMessage.class));

        mailExecutor = task -> {
            throw new TaskRejectedException("queue full");
        };
        runCommitting(() -> service(true, "https://app.example.com", "mailpit")
                .issue(UUID.randomUUID(), "an@example.com"));

        assertThat(meters.get("email.confirmation.send_failed").counter().count()).isEqualTo(1.0);
    }

    @Test
    void spendingALinkConfirmsTheAccountAndDropsThePasswordOnlyTheFirstTime() {
        UUID userId = UUID.randomUUID();
        AppUser user = AppUser.selfRegistered(userId, UUID.randomUUID(), "an@example.com", "hash", "An", "Tran",
                null, null);
        when(tokens.findUserIdByTokenHash(anyString(), eq(EmailLinkPurpose.EMAIL_CONFIRM)))
                .thenReturn(Optional.of(userId));
        when(users.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(tokens.consume(anyString(), eq(EmailLinkPurpose.EMAIL_CONFIRM), eq(userId), eq(NOW))).thenReturn(1);
        EmailConfirmationService service = service(true, "https://app.example.com", "mailpit");

        EmailConfirmationService.Confirmed first = service.confirm("raw-token").orElseThrow();
        EmailConfirmationService.Confirmed again = service.confirm("raw-token").orElseThrow();

        assertThat(first.firstConfirmation()).isTrue();
        assertThat(first.account().hasPassword()).isFalse();
        assertThat(first.account().getUsername()).isNull();
        assertThat(first.account().getName()).isEqualTo("an");
        assertThat(again.firstConfirmation()).isFalse();
    }

    @Test
    void aTokenOfAnotherPurposeNeitherConfirmsNorOpensAnything() {
        EmailConfirmationService service = service(true, "https://app.example.com", "mailpit");
        when(tokens.findUserIdByTokenHash(anyString(), any())).thenReturn(Optional.empty());

        assertThat(service.confirm("setup-token")).isEmpty();
        assertThat(service.openPasswordLink("confirm-token")).isEmpty();

        verify(tokens, never()).consume(anyString(), any(), any(), any());
    }

    @Test
    void aConfirmedAccountWithoutAPasswordIsMailedASetPasswordLink() {
        EmailConfirmationService service = service(true, "https://app.example.com", "mailpit");

        AppUser googleOnly = committing(() -> givenGoogleOnlyAccountResent(service));

        ArgumentCaptor<EmailConfirmationToken> saved = ArgumentCaptor.forClass(EmailConfirmationToken.class);
        verify(tokens).save(saved.capture());
        assertThat(saved.getValue().getPurpose()).isEqualTo(EmailLinkPurpose.PASSWORD_LINK);
        assertThat(saved.getValue().getExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
        verify(tokens).invalidateOpenFor(googleOnly.getId(), NOW);
        ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(mail.capture());
        assertThat(mail.getValue().getSubject()).isEqualTo("Set your Flow-Cortex password");
        assertThat(mail.getValue().getText()).contains("https://app.example.com/set-password?token=");
    }

    @Test
    void aResendForAnAccountWithAPasswordOrNoAccountSendsNothing() {
        AppUser withPassword = AppUser.selfRegistered(UUID.randomUUID(), UUID.randomUUID(), "an@example.com",
                "hash", "An", "Tran", null, NOW);
        when(users.findIdByEmail("an@example.com")).thenReturn(Optional.of(withPassword.getId()));
        when(users.findByIdForUpdate(withPassword.getId())).thenReturn(Optional.of(withPassword));
        when(users.findIdByEmail("nobody@example.com")).thenReturn(Optional.empty());
        EmailConfirmationService service = service(true, "https://app.example.com", "mailpit");

        runCommitting(() -> {
            service.resend("an@example.com");
            service.resend("nobody@example.com");
        });

        verify(tokens, never()).save(any());
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void aSetPasswordLinkWhoseAccountHasAPasswordByNowOpensNothing() {
        AppUser withPassword = AppUser.selfRegistered(UUID.randomUUID(), UUID.randomUUID(), "an@example.com",
                "hash", "An", "Tran", null, NOW);
        when(tokens.findUserIdByTokenHash(anyString(), eq(EmailLinkPurpose.PASSWORD_LINK)))
                .thenReturn(Optional.of(withPassword.getId()));
        when(users.findByIdForUpdate(withPassword.getId())).thenReturn(Optional.of(withPassword));
        when(tokens.consume(anyString(), eq(EmailLinkPurpose.PASSWORD_LINK), eq(withPassword.getId()), eq(NOW)))
                .thenReturn(1);

        assertThat(service(true, "https://app.example.com", "mailpit").openPasswordLink("raw")).isEmpty();
    }

    @Test
    void aSetupTokenIsSpentOnlyAsThisAccountsSetupToken() {
        UUID userId = UUID.randomUUID();
        when(tokens.consume(anyString(), eq(EmailLinkPurpose.PASSWORD_SETUP), eq(userId), eq(NOW))).thenReturn(1);
        EmailConfirmationService service = service(true, "https://app.example.com", "mailpit");

        assertThat(service.spendPasswordSetupToken(userId, "raw")).isTrue();
        assertThat(service.spendPasswordSetupToken(UUID.randomUUID(), "raw")).isFalse();
    }

    @Test
    void aLinkThatCannotBeSpentConfirmsNothing() {
        UUID userId = UUID.randomUUID();
        AppUser user = AppUser.selfRegistered(userId, UUID.randomUUID(), "an@example.com", "hash", "An", "Tran",
                null, null);
        when(tokens.findUserIdByTokenHash(anyString(), eq(EmailLinkPurpose.EMAIL_CONFIRM)))
                .thenReturn(Optional.of(userId));
        when(users.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(tokens.consume(anyString(), eq(EmailLinkPurpose.EMAIL_CONFIRM), eq(userId), eq(NOW))).thenReturn(0);

        assertThat(service(true, "https://app.example.com", "mailpit").confirm("raw-token")).isEmpty();

        assertThat(user.isEmailConfirmed()).isFalse();
        assertThat(user.hasPassword()).isTrue();
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    private AppUser givenGoogleOnlyAccountResent(EmailConfirmationService service) {
        AppUser googleOnly = new AppUser(UUID.randomUUID(), UUID.randomUUID(), "an@example.com", "", "An",
                Role.MEMBER, UserStatus.ACTIVE, false);
        when(users.findIdByEmail("an@example.com")).thenReturn(Optional.of(googleOnly.getId()));
        when(users.findByIdForUpdate(googleOnly.getId())).thenReturn(Optional.of(googleOnly));
        service.resend("an@example.com");
        return googleOnly;
    }

    /** Unbinds the request before the commit, so only a language captured at issue time can reach the mail. */
    private SimpleMailMessage issueAndCommit() {
        EmailConfirmationService service = service(true, "https://app.example/", "mailpit");
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

    private static void runCommitting(Runnable work) {
        committing(() -> {
            work.run();
            return null;
        });
    }

    private static <T> T committing(java.util.function.Supplier<T> work) {
        TransactionSynchronizationManager.initSynchronization();
        try {
            T result = work.get();
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            return result;
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static String tokenIn(String body) {
        Matcher matcher = TOKEN.matcher(body);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }
}
