package com.flowcortex.space.application;

import com.flowcortex.space.api.SpaceAction;
import com.flowcortex.space.api.SpaceRole;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Role to granted actions: the one table deciding what a membership allows. */
final class SpacePolicy {

    private SpacePolicy() {
    }

    private static final Map<SpaceRole, Set<SpaceAction>> GRANTS = Map.of(
            SpaceRole.MANAGER, EnumSet.allOf(SpaceAction.class),
            SpaceRole.EDITOR, EnumSet.noneOf(SpaceAction.class),
            SpaceRole.VIEWER, EnumSet.noneOf(SpaceAction.class));

    static boolean grants(SpaceRole role, SpaceAction action) {
        if (role == null || action == null) {
            return false;
        }
        return GRANTS.get(role).contains(action);
    }
}
