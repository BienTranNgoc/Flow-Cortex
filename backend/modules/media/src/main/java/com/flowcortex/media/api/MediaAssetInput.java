package com.flowcortex.media.api;

import java.util.UUID;

/** An owner-verified asset and the object-store input derived from it. */
public record MediaAssetInput(UUID assetId, MediaJobInput input) {
}
