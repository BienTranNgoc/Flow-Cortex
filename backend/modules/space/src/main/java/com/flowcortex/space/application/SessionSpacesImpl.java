package com.flowcortex.space.application;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.space.api.SessionSpaces;
import com.flowcortex.space.api.SpaceContext;
import com.flowcortex.space.infrastructure.SpaceRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
class SessionSpacesImpl implements SessionSpaces {

    private final SpaceSwitch spaceSwitch;
    private final SpaceRepository spaces;

    SessionSpacesImpl(SpaceSwitch spaceSwitch, SpaceRepository spaces) {
        this.spaceSwitch = spaceSwitch;
        this.spaces = spaces;
    }

    @Override
    public SpaceContext sessionSpace(UUID userId) {
        if (!spaceSwitch.enabled()) {
            throw new LocalizedStatusException(HttpStatus.SERVICE_UNAVAILABLE, new LocalizedMessage("feature.turnedOff"));
        }
        return spaces.ensurePersonal(userId);
    }
}
