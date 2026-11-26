package com.flowcortex.notification.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowcortex.notification.application.Audience;
import com.flowcortex.notification.application.NotificationService;
import com.flowcortex.notification.application.NotificationService.Listing;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.user.api.CurrentUser;
import com.flowcortex.user.api.UserDirectory;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

class NotificationsControllerTest {

    private final NotificationService service = mock(NotificationService.class);
    private final UserDirectory userDirectory = mock(UserDirectory.class);
    private final NotificationsController controller = new NotificationsController(service, userDirectory);
    private final UUID userId = UUID.randomUUID();
    private final Jwt jwt = mock(Jwt.class);

    private void currentUserExists() {
        CurrentUser user = mock(CurrentUser.class);
        when(user.id()).thenReturn(userId);
        when(jwt.getSubject()).thenReturn(userId.toString());
        when(userDirectory.findById(userId)).thenReturn(Optional.of(user));
    }

    @Test
    void aTokenWhoseUserIsGoneIsNotFound() {
        when(jwt.getSubject()).thenReturn(userId.toString());
        when(userDirectory.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.unreadCount(jwt, null)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void aMalformedUnknownOrForeignIdIsTheSameNotFound() {
        currentUserExists();
        UUID unknown = UUID.randomUUID();
        when(service.markRead(userId, unknown)).thenReturn(false);

        assertThatThrownBy(() -> controller.markRead(jwt, "abc")).isInstanceOf(NotFoundException.class)
                .extracting(e -> ((NotFoundException) e).getDetail().code()).isEqualTo("notification.notFound");
        assertThatThrownBy(() -> controller.markRead(jwt, unknown.toString())).isInstanceOf(NotFoundException.class)
                .extracting(e -> ((NotFoundException) e).getDetail().code()).isEqualTo("notification.notFound");
    }

    @Test
    void markingOwnNotificationReadAnswers204AndReadAllReportsTheCount() {
        currentUserExists();
        UUID own = UUID.randomUUID();
        when(service.markRead(userId, own)).thenReturn(true);
        when(service.markAllRead(userId, Audience.MEMBER)).thenReturn(4);
        when(service.unreadCount(userId, Audience.MEMBER)).thenReturn(2L);

        assertThat(controller.markRead(jwt, own.toString()).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(controller.markAllRead(jwt, null).marked()).isEqualTo(4);
        assertThat(controller.unreadCount(jwt, null).unreadCount()).isEqualTo(2);
    }

    @Test
    void anUnknownAudienceIsABadRequest() {
        currentUserExists();

        assertThatThrownBy(() -> controller.list(jwt, "staff", null, null, null, Locale.ENGLISH))
                .isInstanceOf(LocalizedStatusException.class);
        assertThatThrownBy(() -> controller.unreadCount(jwt, "staff")).isInstanceOf(LocalizedStatusException.class);
        assertThatThrownBy(() -> controller.markAllRead(jwt, "staff")).isInstanceOf(LocalizedStatusException.class);
        verifyNoInteractions(service);
    }

    @Test
    void theAudienceReachesTheService() {
        currentUserExists();
        when(service.list(userId, Audience.ADMIN, "unread", "1", "5", Locale.ENGLISH))
                .thenReturn(new Listing(List.of(), 1, 5, false, 3));
        when(service.unreadCount(userId, Audience.ADMIN)).thenReturn(3L);
        when(service.markAllRead(userId, Audience.ADMIN)).thenReturn(3);

        assertThat(controller.list(jwt, "admin", "unread", "1", "5", Locale.ENGLISH).unreadCount()).isEqualTo(3);
        assertThat(controller.unreadCount(jwt, "admin").unreadCount()).isEqualTo(3);
        assertThat(controller.markAllRead(jwt, "admin").marked()).isEqualTo(3);
    }
}
