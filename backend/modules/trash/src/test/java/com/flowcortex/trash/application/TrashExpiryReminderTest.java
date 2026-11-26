package com.flowcortex.trash.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.flowcortex.mail.api.Mailer;
import com.flowcortex.trash.infrastructure.TrashEntryStore;
import com.flowcortex.user.api.UserDirectory;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;

class TrashExpiryReminderTest {

    @Test
    void theSwitchOnWithoutAPublicUrlFailsNamingTheProperty() {
        assertThatThrownBy(() -> reminder(true, ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("flowcortex.app.public-url");
        assertThatThrownBy(() -> reminder(true, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("flowcortex.app.public-url");
    }

    @Test
    void theSwitchOffNeedsNoPublicUrl() {
        assertThatCode(() -> reminder(false, "")).doesNotThrowAnyException();
    }

    private static TrashExpiryReminder reminder(boolean enabled, String publicUrl) {
        TrashProperties properties = new TrashProperties(enabled, Duration.ofDays(30), Duration.ofDays(3),
                Duration.ofHours(1), Duration.ofHours(1));
        return new TrashExpiryReminder(mock(TrashEntryStore.class), new TrashProviders(List.of()), properties,
                Clock.systemUTC(), mock(PlatformTransactionManager.class), mock(ApplicationEventPublisher.class),
                mock(UserDirectory.class), mock(Mailer.class), publicUrl);
    }
}
