package com.flowcortex.user.passwordreset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.shared.web.LocaleConfig;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.user.application.AccountCredentials;
import com.flowcortex.user.application.PasswordResetRateLimitedException;
import com.flowcortex.user.application.RateLimit;
import com.flowcortex.user.domain.AppUser;
import com.flowcortex.user.domain.Role;
import com.flowcortex.user.domain.UserStatus;
import com.flowcortex.user.emailconfirm.EmailConfirmationService;
import com.flowcortex.user.infrastructure.AppUserRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class PasswordResetServiceTest {

    private static final RateLimit HOURLY = new RateLimit(5, Duration.ofHours(1));

    private static PasswordResetService service(boolean enabled, String publicUrl, String mailHost) {
        return new PasswordResetService(mock(AppUserRepository.class), mock(EmailConfirmationService.class),
                mock(AccountCredentials.class), new PasswordResetProperties(enabled, Duration.ofHours(1), HOURLY,
                        HOURLY, HOURLY), Clock.systemUTC(), publicUrl, mailHost);
    }

    @Test
    void resetOnWithoutAPublicUrlOrAMailHostRefusesToStartNamingWhatIsMissing() {
        for (String blank : new String[] {"", "  ", null}) {
            assertThatThrownBy(() -> service(true, blank, "mailpit"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("FLOWCORTEX_APP_PUBLIC_URL");
            assertThatThrownBy(() -> service(true, "https://app.example.com", blank))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SPRING_MAIL_HOST");
        }
    }

    @Test
    void completingAResetRevokesEveryCredentialOfTheAccount() {
        AppUser account = new AppUser(UUID.randomUUID(), UUID.randomUUID(), "admin@example.com", "old", "Admin",
                Role.ADMIN, UserStatus.ACTIVE);
        EmailConfirmationService links = mock(EmailConfirmationService.class);
        when(links.spendPasswordReset("raw")).thenReturn(Optional.of(account));
        AccountCredentials credentials = mock(AccountCredentials.class);
        PasswordResetService resets = new PasswordResetService(mock(AppUserRepository.class), links, credentials,
                new PasswordResetProperties(false, Duration.ofHours(1), HOURLY, HOURLY, HOURLY), Clock.systemUTC(),
                "", "");

        resets.reset("raw", "new-hash");

        verify(credentials).revokeAllFor(account.getId());
    }

    @Test
    void resetOffStartsWithoutEither() {
        assertThat(service(false, "", "")).isNotNull();
    }

    @Test
    void theRefusedLinkProblemReadsInTheRequestsLanguage() {
        try (AnnotationConfigApplicationContext messages = new AnnotationConfigApplicationContext(LocaleConfig.class)) {
            var detail = PasswordResetService.linkInvalid().getDetail();

            assertThat(messages.getMessage(detail, Locale.ENGLISH)).isEqualTo("This reset link is no longer valid");
            assertThat(messages.getMessage(detail, Locale.forLanguageTag("vi")))
                    .isEqualTo("Liên kết đặt lại mật khẩu này không còn hiệu lực. Hãy yêu cầu gửi liên kết mới.");
            PasswordResetRateLimitedException limited = new PasswordResetRateLimitedException(Duration.ofSeconds(5));
            assertThat(messages.getMessage(new LocalizedMessage(limited.getDetailMessageCode()), Locale.ENGLISH))
                    .isEqualTo("Too many reset requests. Try again later.");
            assertThat(messages.getMessage(new LocalizedMessage(limited.getDetailMessageCode()),
                    Locale.forLanguageTag("vi"))).startsWith("Bạn đã yêu cầu đặt lại mật khẩu quá nhiều lần");
        }
    }

    @Test
    void aValidityThatIsNotPositiveIsRefusedNamingTheProperty() {
        assertThatThrownBy(() -> new PasswordResetProperties(true, Duration.ZERO, HOURLY, HOURLY, HOURLY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("flowcortex.auth.password-reset.validity");
    }
}
