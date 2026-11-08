package com.flowcortex.space.api;

import java.util.UUID;

/** A space and the role the asking account holds in it. */
public record SpaceContext(UUID spaceId, SpaceRole role) {
}
