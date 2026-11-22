package com.flowcortex.notification.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flowcortex.notification.application.NotificationService;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.user.api.CurrentUser;
import com.flowcortex.user.api.UserDirectory;
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

        assertThatThrownBy(() -> controller.unreadCount(jwt)).isInstanceOf(NotFoundException.class);
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
        when(service.markAllRead(userId)).thenReturn(4);
        when(service.unreadCount(userId)).thenReturn(2L);

        assertThat(controller.markRead(jwt, own.toString()).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(controller.markAllRead(jwt).marked()).isEqualTo(4);
        assertThat(controller.unreadCount(jwt).unreadCount()).isEqualTo(2);
    }
}
