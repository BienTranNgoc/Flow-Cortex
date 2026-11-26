package com.flowcortex.entity.application;

import com.flowcortex.asset.api.SpaceAssetSummary;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** An entity as a member reads it: its content, with every part joined to its library asset. */
public record EntityView(UUID id, String kind, String handle, String name, Map<String, Object> fields,
        String categoryLabel, List<String> tags, List<PartView> parts, int version, Instant createdAt,
        Instant updatedAt) {

    /** A part and the asset it points at. */
    public record PartView(UUID id, String role, String label, int sort, SpaceAssetSummary asset) {
    }
}
