package com.flowcortex.trash.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** The shape of a restore or purge body. */
public final class TrashRequestRules {

    static final int MAX_ITEMS = 100;
    private static final String ITEMS_FIELD = "items";

    private TrashRequestRules() {
    }

    /** One {@code {type, id}} of a request body. */
    public record ItemRef(String type, String id) {
    }

    /** The name of the offending field, {@code items}, when the list is not 1-100 distinct well-formed pairs. */
    public static Optional<String> checkItems(List<ItemRef> items) {
        if (items == null || items.isEmpty() || items.size() > MAX_ITEMS) {
            return Optional.of(ITEMS_FIELD);
        }
        Set<ItemRef> seen = new HashSet<>();
        for (ItemRef item : items) {
            boolean wellFormed = item != null && TrashKeys.validType(item.type()) && TrashKeys.validItemId(item.id());
            if (!wellFormed || !seen.add(item)) {
                return Optional.of(ITEMS_FIELD);
            }
        }
        return Optional.empty();
    }
}
