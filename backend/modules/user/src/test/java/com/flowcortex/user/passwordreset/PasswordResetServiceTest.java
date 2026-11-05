package com.flowcortex.user.passwordreset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.flowcortex.user.application.RateLimit;
import com.flowcortex.user.application.RefreshTokenService;
import com.flowcortex.user.emailconfirm.EmailConfirmationService;
import com.flowcortex.user.infrastructure.AppUserRepository;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class PasswordResetServiceTest {

    private static final RateLimit HOURLY = new RateLimit(5, Duration.ofHours(1));

    private static PasswordResetService service(boolean enabled, String publicUrl, String mailHost) {
        return new PasswordResetService(mock(AppUserRepository.class), mock(EmailConfirmationService.class),
                mock(RefreshTokenService.class), new PasswordResetProperties(enabled, Duration.ofHours(1), HOURLY,
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
    void resetOffStartsWithoutEither() {
        assertThat(service(false, "", "")).isNotNull();
    }

    @Test
    void aValidityThatIsNotPositiveIsRefusedNamingTheProperty() {
        assertThatThrownBy(() -> new PasswordResetProperties(true, Duration.ZERO, HOURLY, HOURLY, HOURLY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("flowcortex.auth.password-reset.validity");
    }
}
