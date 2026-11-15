package com.flowcortex.space.application;

import com.flowcortex.space.api.PersonalSpaces;
import com.flowcortex.space.infrastructure.SpaceRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
class PersonalSpacesImpl implements PersonalSpaces {

    private final SpaceRepository spaces;

    PersonalSpacesImpl(SpaceRepository spaces) {
        this.spaces = spaces;
    }

    @Override
    public UUID personalSpaceOf(UUID ownerId) {
        return spaces.ensurePersonal(ownerId).spaceId();
    }
}
