package com.flowcortex.settings.api;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Resolves the accounts behind a change; implemented by the module that owns accounts. */
public interface SettingActors {

    /** Emails by account id; an account that no longer exists is absent from the map. */
    Map<UUID, String> emailsOf(Collection<UUID> ids);
}
