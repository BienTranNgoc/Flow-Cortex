package com.flowcortex.asset.api;

import java.util.UUID;

/**
 * What another module may know about a library asset: no storage location and no URL.
 *
 * @param kind {@code video}, {@code image} or {@code audio}
 * @param mimeType media type of the stored file
 */
public record SpaceAssetSummary(UUID id, String kind, String title, String mimeType) {
}
