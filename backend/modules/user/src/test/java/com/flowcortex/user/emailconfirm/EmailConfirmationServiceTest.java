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

import com.flowcortex.user.domain.AppUser;
import com.flowcortex.user.domain.Role;
import com.flowcortex.user.domain.UserStatus;
import com.flowcortex.user.infrastructure.AppUserRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class EmailConfirmationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-18T09:00:00Z");

    private final EmailConfirmationTokenRepository tokens = mock(EmailConfirmationTokenRepository.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private EmailConfirmationService service(boolean enabled, String publicUrl, String mailHost) {
        return new EmailConfirmationService(tokens, users, mailSender,
                new EmailConfirmationProperties(enabled, Duration.ofHours(24), "no-reply@flow-cortex.dev",
                        Duration.ofHours(72), Duration.ofMinutes(30)),
                Clock.fixed(NOW, ZoneOffset.UTC), publicUrl, mailHost, meters);
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
    void aMailTheServerRefusesIsCountedAndDoesNotFailTheCaller() {
        doThrow(new MailSendException("refused")).when(mailSender).send(any(SimpleMailMessage.class));
        EmailConfirmationService service = service(true, "https://app.example.com/", "mailpit");

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.issue(UUID.randomUUID(), "an@example.com");
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

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
        AppUser googleOnly = new AppUser(UUID.randomUUID(), UUID.randomUUID(), "an@example.com", "", "An",
                Role.MEMBER, UserStatus.ACTIVE, false);
        when(users.findIdByEmail("an@example.com")).thenReturn(Optional.of(googleOnly.getId()));
        when(users.findByIdForUpdate(googleOnly.getId())).thenReturn(Optional.of(googleOnly));
        EmailConfirmationService service = service(true, "https://app.example.com", "mailpit");

        runCommitting(() -> service.resend("an@example.com"));

        ArgumentCaptor<EmailConfirmationToken> saved = ArgumentCaptor.forClass(EmailConfirmationToken.class);
        verify(tokens).save(saved.capture());
        assertThat(saved.getValue().getPurpose()).isEqualTo(EmailLinkPurpose.PASSWORD_LINK);
        assertThat(saved.getValue().getExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
        verify(tokens).invalidateOpenFor(googleOnly.getId(), NOW);
        ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(mail.capture());
        assertThat(mail.getValue().getSubject()).isEqualTo(EmailConfirmationService.PASSWORD_LINK_SUBJECT);
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

    private static void runCommitting(Runnable work) {
        TransactionSynchronizationManager.initSynchronization();
        try {
            work.run();
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
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
}
