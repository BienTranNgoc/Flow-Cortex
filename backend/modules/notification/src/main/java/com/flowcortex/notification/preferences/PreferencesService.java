package com.flowcortex.notification.preferences;

import com.flowcortex.mail.api.MailCategory;
import com.flowcortex.mail.api.MailSubscriptions;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The member's three choices: the in-app flag is stored here, the two email switches are the mail lists. */
@Service
@Transactional
class PreferencesService {

    private final PreferencesRepository repository;
    private final MailSubscriptions subscriptions;
    private final Clock clock;

    PreferencesService(PreferencesRepository repository, MailSubscriptions subscriptions, Clock clock) {
        this.repository = repository;
        this.subscriptions = subscriptions;
        this.clock = clock;
    }

    /** The member's choices; every one is on until changed. */
    record Preferences(boolean inApp, boolean careEmail, boolean marketingEmail) {
    }

    @Transactional(readOnly = true)
    Preferences get(UUID userId) {
        return new Preferences(repository.inApp(userId),
                subscriptions.subscribed(userId, MailCategory.CARE),
                subscriptions.subscribed(userId, MailCategory.PROMOTIONAL));
    }

    /** Applies the choices the patch names in one transaction and returns the full set. */
    Preferences update(UUID userId, PreferencePatch patch) {
        if (patch.inApp() != null) {
            repository.setInApp(userId, patch.inApp(), Instant.now(clock));
        }
        if (patch.careEmail() != null) {
            subscriptions.setSubscribed(userId, MailCategory.CARE, patch.careEmail());
        }
        if (patch.marketingEmail() != null) {
            subscriptions.setSubscribed(userId, MailCategory.PROMOTIONAL, patch.marketingEmail());
        }
        return get(userId);
    }
}
