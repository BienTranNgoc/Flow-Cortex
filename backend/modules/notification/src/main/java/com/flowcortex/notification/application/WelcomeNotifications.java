package com.flowcortex.notification.application;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.flowcortex.notification.api.NotificationRequested;
import com.flowcortex.user.api.UserActivatedEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Every account gets one welcome notification once it becomes usable. The id is derived from the
 * member, so a redelivered or repeated activation stores nothing new.
 */
@Component
class WelcomeNotifications {

    private final NotificationInbox inbox;

    WelcomeNotifications(NotificationInbox inbox) {
        this.inbox = inbox;
    }

    @ApplicationModuleListener
    void on(UserActivatedEvent event) {
        UUID id = UUID.nameUUIDFromBytes(("welcome:" + event.userId()).getBytes(UTF_8));
        inbox.deliver(new NotificationRequested(id, event.userId(), "welcome", "notification.welcome.title",
                "notification.welcome.body", List.of(), "/workflows"));
    }
}
