package com.flowcortex.entity.domain;

import java.util.UUID;

/** A part as a request names it: the library asset, its role in the entity and an optional label. */
public record PartInput(UUID assetId, String role, String label) {
}
