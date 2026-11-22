package com.flowcortex.user.infrastructure;

import com.flowcortex.settings.api.SettingActors;
import com.flowcortex.user.api.UserDirectory;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Lets the settings module name the admins behind a change without depending on this module. */
@Component
class SettingActorsAdapter implements SettingActors {

    private final UserDirectory users;

    SettingActorsAdapter(UserDirectory users) {
        this.users = users;
    }

    @Override
    public Map<UUID, String> emailsOf(Collection<UUID> ids) {
        return users.findEmailsByIds(ids);
    }
}
