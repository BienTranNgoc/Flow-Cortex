package com.flowcortex.space.application;

import com.flowcortex.space.infrastructure.SpaceRepository;
import com.flowcortex.user.api.UserActivatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Every account gets its personal space once it becomes usable, whatever the spaces switch says.
 * A failure is not caught, so the persistent event stays incomplete and is delivered again.
 */
@Component
class PersonalSpaceProvisioner {

    private static final Logger LOG = LoggerFactory.getLogger(PersonalSpaceProvisioner.class);

    private final SpaceRepository spaces;

    PersonalSpaceProvisioner(SpaceRepository spaces) {
        this.spaces = spaces;
    }

    @ApplicationModuleListener
    void on(UserActivatedEvent event) {
        spaces.ensurePersonal(event.userId());
        LOG.info("Spaces: outcome=ensured userId={}", event.userId());
    }
}
