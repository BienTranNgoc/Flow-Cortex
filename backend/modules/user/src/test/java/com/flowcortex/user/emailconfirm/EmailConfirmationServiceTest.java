package com.flowcortex.user.emailconfirm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.mail.api.MailKey;
import com.flowcortex.mail.api.MailOutcome;
import com.flowcortex.mail.api.Mailer;
import com.flowcortex.mail.api.OutgoingMail;
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
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpHeaders;
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
    private final Mailer mailer = mock(Mailer.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private TaskExecutor mailExecutor = Runnable::run;

    private EmailConfirmationService service(boolean enabled, String publicUrl, String mailHost) {
        return new EmailConfirmationService(tokens, users, mailer, mailExecutor,
                new EmailConfirmationProperties(enabled, Duration.ofHours(24), Duration.ofHours(72),
                        Duration.ofMinutes(30), 500),
                Clock.fixed(NOW, ZoneOffset.UTC), publicUrl, mailHost, meters,
                LOCALE.getBean(RequestLocale.class));
    }

    @BeforeEach
    void relayAccepts() {
        when(mailer.send(any(OutgoingMail.class))).thenReturn(MailOutcome.SENT);
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
    void theLanguageOfTheRegistrationRequestOutlivesTheRequestUntilTheMailLeaves() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.ACCEPT_LANGUAGE, "vi-VN");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        OutgoingMail mail = issueAndCommit();

        assertThat(mail.key()).isEqualTo(MailKey.ACTIVATION);
        assertThat(mail.locale().getLanguage()).isEqualTo("vi");
        assertThat(mail.to()).isEqualTo("member@example.com");
        String link = (String) mail.values().get(0);
        assertThat(link).isEqualTo("https://app.example/confirm-email?token=" + tokenIn(link));
        assertThat(mail.values().get(1)).isEqualTo(24L);
    }

    @Test
    void withoutAnAcceptLanguageTheResetLinkIsMailedInEnglishWithTheValidityInWholeUnits() {
        EmailConfirmationService service = service(true, "https://app.example.com", "mailpit");
        AppUser account = resetAccount();

        runCommitting(() -> service.mailPasswordReset(account, Duration.ofHours(1)));
        runCommitting(() -> service.mailPasswordReset(account, Duration.ofMinutes(90)));

        List<OutgoingMail> sent = sentMails(2);
        OutgoingMail hour = sent.get(0);
        assertThat(hour.key()).isEqualTo(MailKey.RESET_PASSWORD);
        assertThat(hour.locale()).isEqualTo(Locale.ENGLISH);
        String link = hour.values().get(0).toString();
        assertThat(link).isEqualTo("https://app.example.com/reset-password#token=" + fragmentTokenIn(link));
        assertThat(english(hour.values().get(1))).isEqualTo("1 hour");
        assertThat(english(sent.get(1).values().get(1))).isEqualTo("90 minutes");
    }

    @Test
    void aResetOfAnUnconfirmedAccountIsMailedTheResetWordedConfirmation() {
        EmailConfirmationService service = service(true, "https://app.example.com", "mailpit");
        AppUser unconfirmed = AppUser.selfRegistered(UUID.randomUUID(), UUID.randomUUID(), "an@example.com", "hash",
                "An", "Tran", null, null);

        runCommitting(() -> service.reissueLinkFor(unconfirmed, EmailConfirmationService.LinkRequest.PASSWORD_RESET));

        OutgoingMail mail = sentMails(1).getFirst();
        assertThat(mail.key()).isEqualTo(MailKey.ACTIVATION_AFTER_RESET_REQUEST);
        assertThat(mail.values()).hasSize(2);
    }

    @Test
    void aMailTheServerRefusesIsCountedAndDoesNotFailTheCaller() {
        when(mailer.send(any(OutgoingMail.class))).thenReturn(MailOutcome.FAILED);
        EmailConfirmationService service = service(true, "https://app.example.com/", "mailpit");

        runCommitting(() -> service.issue(UUID.randomUUID(), "an@example.com"));

        assertThat(meters.get("email.confirmation.send_failed").counter().count()).isEqualTo(1.0);
    }

    @Test
    void aMailTheMailerRejectsAsMalformedIsCountedAndDoesNotFailTheCaller() {
        when(mailer.send(any(OutgoingMail.class))).thenThrow(new IllegalArgumentException("no address"));
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
        verify(mailer, never()).send(any(OutgoingMail.class));
        assertThat(queued).hasSize(1);
        queued.getFirst().run();
        verify(mailer).send(any(OutgoingMail.class));

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
        OutgoingMail mail = sentMails(1).getFirst();
        assertThat(mail.key()).isEqualTo(MailKey.PASSWORD_SETUP_LINK);
        assertThat(mail.values().get(0).toString()).startsWith("https://app.example.com/set-password?token=");
        assertThat(mail.values().get(1)).isEqualTo(30L);
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
        verify(mailer, never()).send(any(OutgoingMail.class));
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
        verify(mailer, never()).send(any(OutgoingMail.class));
    }

    private static AppUser resetAccount() {
        return AppUser.selfRegistered(UUID.randomUUID(), UUID.randomUUID(), "an@example.com", "hash", "An", "Tran",
                null, NOW);
    }

    private static String fragmentTokenIn(String body) {
        Matcher matcher = Pattern.compile("reset-password#token=([A-Za-z0-9_-]+)").matcher(body);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
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
    private OutgoingMail issueAndCommit() {
        EmailConfirmationService service = service(true, "https://app.example/", "mailpit");
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.issue(UUID.randomUUID(), "member@example.com");
            RequestContextHolder.resetRequestAttributes();
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        return sentMails(1).getFirst();
    }

    private List<OutgoingMail> sentMails(int expected) {
        ArgumentCaptor<OutgoingMail> sent = ArgumentCaptor.forClass(OutgoingMail.class);
        verify(mailer, times(expected)).send(sent.capture());
        return sent.getAllValues();
    }

    private static String english(Object value) {
        return LOCALE.getMessage((MessageSourceResolvable) value, Locale.ENGLISH);
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
