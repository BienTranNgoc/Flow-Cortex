package com.flowcortex.notification.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.notification.api.NotificationRequested;
import com.flowcortex.notification.infrastructure.NotificationRepository;
import com.flowcortex.user.api.UserActivatedEvent;
import com.flowcortex.user.api.UserDirectory;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NotificationInboxTest {

    private static final Instant NOW = Instant.parse("2026-11-11T09:00:00Z");

    private final NotificationRepository repository = mock(NotificationRepository.class);
    private final UserDirectory userDirectory = mock(UserDirectory.class);
    private final InboxSignals signals = mock(InboxSignals.class);
    private final NotificationInbox inbox = new NotificationInbox(repository, userDirectory, signals,
            new NotificationProperties(3), Clock.fixed(NOW, ZoneOffset.UTC));
    private final UUID member = UUID.randomUUID();
    private final NotificationRequested request = NotificationRequested.of(member, "test", "notification.welcome.title");

    private void memberExists(boolean exists) {
        when(userDirectory.findEmailsByIds(Set.of(member))).thenReturn(exists ? Map.of(member, "a@b.c") : Map.of());
    }

    @Test
    void aStoredRequestIsTrimmedToTheCapAndSignalled() {
        memberExists(true);
        when(repository.inAppEnabled(member)).thenReturn(true);
        when(repository.insert(request, NOW)).thenReturn(1);

        inbox.deliver(request);

        verify(repository).trim(member, 3);
        verify(signals).changed(member);
    }

    @Test
    void aDuplicateIsNeitherTrimmedNorSignalled() {
        memberExists(true);
        when(repository.inAppEnabled(member)).thenReturn(true);
        when(repository.insert(request, NOW)).thenReturn(0);

        inbox.deliver(request);

        verify(repository, never()).trim(any(), anyInt());
        verify(signals, never()).changed(any());
    }

    @Test
    void aGoneRecipientStoresNothing() {
        memberExists(false);

        inbox.deliver(request);

        verify(repository, never()).insert(any(), any());
        verify(signals, never()).changed(any());
    }

    @Test
    void anInAppOffRecipientStoresNothing() {
        memberExists(true);
        when(repository.inAppEnabled(member)).thenReturn(false);

        inbox.on(request);

        verify(repository, never()).insert(any(), any());
    }

    @Test
    void theWelcomeIdIsTheSameForTheSameMember() {
        WelcomeNotifications welcome = new WelcomeNotifications(inbox);
        memberExists(true);
        when(repository.inAppEnabled(member)).thenReturn(true);
        ArgumentCaptor<NotificationRequested> stored = ArgumentCaptor.forClass(NotificationRequested.class);

        welcome.on(new UserActivatedEvent(member));
        welcome.on(new UserActivatedEvent(member));

        verify(repository, org.mockito.Mockito.times(2)).insert(stored.capture(), eq(NOW));
        org.assertj.core.api.Assertions.assertThat(stored.getAllValues().get(0).id())
                .isEqualTo(stored.getAllValues().get(1).id());
        org.assertj.core.api.Assertions.assertThat(stored.getValue().link()).isEqualTo("/workflows");
        org.assertj.core.api.Assertions.assertThat(stored.getValue().type()).isEqualTo("welcome");
    }
}
