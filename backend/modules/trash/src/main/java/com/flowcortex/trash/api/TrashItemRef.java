package com.flowcortex.trash.api;

import java.util.UUID;

/** The trashed item a provider is asked to restore or purge, with the account that owns its entry. */
public record TrashItemRef(String type, String itemId, UUID ownerId) {
}
