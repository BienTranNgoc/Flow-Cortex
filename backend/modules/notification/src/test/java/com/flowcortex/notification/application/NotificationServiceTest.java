package com.flowcortex.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.notification.infrastructure.NotificationRepository;
import com.flowcortex.notification.infrastructure.NotificationRepository.NotificationRow;
import com.flowcortex.notification.infrastructure.NotificationRepository.RowPage;
import com.flowcortex.shared.web.LocalizedStatusException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

class NotificationServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-11T09:00:00Z");

    private final NotificationRepository repository = mock(NotificationRepository.class);
    private final InboxSignals signals = mock(InboxSignals.class);
    private final NotificationService service = new NotificationService(repository,
            new NotificationText(messages()), signals, Clock.fixed(NOW, ZoneOffset.UTC));
    private final UUID member = UUID.randomUUID();

    private static ResourceBundleMessageSource messages() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasenames("i18n/messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return source;
    }

    @Test
    void aPageIsRenderedInTheReadersLanguageWithTheUnreadCount() {
        NotificationRow row = new NotificationRow(UUID.randomUUID(), "welcome", "notification.welcome.title",
                "notification.welcome.body", List.of(), "/workflows", null, NOW);
        when(repository.page(member, true, 20, 20)).thenReturn(new RowPage(List.of(row), true));
        when(repository.unreadCount(member)).thenReturn(7L);

        NotificationService.Listing listing = service.list(member, "unread", "1", null, Locale.forLanguageTag("vi"));

        assertThat(listing.items()).singleElement().satisfies(item -> {
            assertThat(item.title()).isEqualTo("Chào mừng bạn đến với Nodi");
            assertThat(item.read()).isFalse();
        });
        assertThat(listing.hasMore()).isTrue();
        assertThat(listing.page()).isEqualTo(1);
        assertThat(listing.size()).isEqualTo(20);
        assertThat(listing.unreadCount()).isEqualTo(7);
    }

    @Test
    void outOfRangeParametersAreRefusedByName() {
        for (String[] args : new String[][] {{"all", "-1", "20"}, {"all", "0", "51"}, {"all", "0", "0"},
                {"archived", "0", "20"}, {"all", "x", "20"}, {"all", "0", "2.5"}}) {
            assertThatThrownBy(() -> service.list(member, args[0], args[1], args[2], Locale.ENGLISH))
                    .isInstanceOf(LocalizedStatusException.class);
        }
    }

    @Test
    void markingAnUnreadNotificationSignalsOnce() {
        UUID id = UUID.randomUUID();
        when(repository.markRead(id, member, NOW)).thenReturn(1);

        assertThat(service.markRead(member, id)).isTrue();

        verify(signals).changed(member);
    }

    @Test
    void markingAnAlreadyReadNotificationSignalsNothingAndAnUnknownOneIsAbsent() {
        UUID read = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        when(repository.exists(read, member)).thenReturn(true);

        assertThat(service.markRead(member, read)).isTrue();
        assertThat(service.markRead(member, unknown)).isFalse();

        verify(signals, never()).changed(any());
    }

    @Test
    void readAllSignalsOnlyWhenSomethingChanged() {
        when(repository.markAllRead(member, NOW)).thenReturn(3, 0);

        assertThat(service.markAllRead(member)).isEqualTo(3);
        assertThat(service.markAllRead(member)).isZero();

        verify(signals, org.mockito.Mockito.times(1)).changed(member);
    }
}
