package com.flowcortex.trash.api;

import java.util.UUID;

/**
 * One soft delete to index. {@code name} may be null or blank: the item is then listed as untitled.
 * {@code locationCode} is a message bundle code the list renders in the reader's language.
 */
public record TrashRecord(String type, String itemId, UUID ownerId, UUID deletedBy, String name, String locationCode) {
}
