package com.flowcortex.space.application;

import com.flowcortex.space.api.SpaceAccess;
import com.flowcortex.space.api.SpaceAction;
import com.flowcortex.space.infrastructure.SpaceRepository;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

@Service
class SpaceAccessImpl implements SpaceAccess {

    private final SpaceRepository spaces;

    SpaceAccessImpl(SpaceRepository spaces) {
        this.spaces = spaces;
    }

    @Override
    public boolean can(UUID userId, SpaceAction action, UUID spaceId) {
        if (userId == null || action == null || spaceId == null) {
            return false;
        }
        return spaces.findMemberRole(spaceId, userId)
                .map(role -> SpacePolicy.grants(role, action))
                .orElse(false);
    }

    @Override
    public void assertCan(UUID userId, SpaceAction action, UUID spaceId, Supplier<? extends RuntimeException> notFound) {
        if (!can(userId, action, spaceId)) {
            throw notFound.get();
        }
    }
}
