package com.flowcortex.entity.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The editable content of an entity at one version: what a version row stores and what a diff or a restore
 * compares. Holds ids and text only, never a URL or bytes. {@code tags} are labels, {@code categoryLabel} may be {@code null}.
 */
public record EntitySnapshot(
        String name,
        String handle,
        Map<String, Object> fields,
        String categoryLabel,
        List<String> tags,
        List<Part> parts) {

    /** A part as stored: the library asset, its role, an optional label and its position. */
    public record Part(UUID assetId, String role, String label, int sort) {
    }

    public EntitySnapshot {
        fields = fields == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        tags = tags == null ? List.of() : List.copyOf(tags);
        parts = parts == null ? List.of() : List.copyOf(parts);
    }

    public EntitySnapshot withName(String newName) {
        return new EntitySnapshot(newName, handle, fields, categoryLabel, tags, parts);
    }

    public EntitySnapshot withHandle(String newHandle) {
        return new EntitySnapshot(name, newHandle, fields, categoryLabel, tags, parts);
    }

    public EntitySnapshot withFields(Map<String, Object> newFields) {
        return new EntitySnapshot(name, handle, newFields, categoryLabel, tags, parts);
    }

    public EntitySnapshot withCategoryLabel(String newCategoryLabel) {
        return new EntitySnapshot(name, handle, fields, newCategoryLabel, tags, parts);
    }

    public EntitySnapshot withTags(List<String> newTags) {
        return new EntitySnapshot(name, handle, fields, categoryLabel, newTags, parts);
    }

    public EntitySnapshot withParts(List<Part> newParts) {
        return new EntitySnapshot(name, handle, fields, categoryLabel, tags, newParts);
    }
}
