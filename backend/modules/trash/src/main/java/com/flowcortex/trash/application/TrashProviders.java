package com.flowcortex.trash.application;

import com.flowcortex.trash.api.TrashProvider;
import com.flowcortex.trash.domain.TrashKeys;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Every {@link TrashProvider} bean, checked once at startup: a malformed or doubly claimed type fails the
 * context, naming it. The order is purge order: lower rank first, ties by type.
 */
@Component
public class TrashProviders {

    private final Map<String, TrashProvider> byType;
    private final List<TrashProvider> ranked;

    public TrashProviders(List<TrashProvider> providers) {
        Map<String, TrashProvider> seen = new LinkedHashMap<>();
        for (TrashProvider provider : providers) {
            String type = provider.type();
            if (!TrashKeys.validType(type)) {
                throw new IllegalStateException("Trash provider type is not a valid key: " + type);
            }
            if (seen.putIfAbsent(type, provider) != null) {
                throw new IllegalStateException("Two Trash providers claim the type " + type);
            }
        }
        this.ranked = seen.values().stream()
                .sorted(Comparator.comparingInt(TrashProvider::purgeRank).thenComparing(TrashProvider::type))
                .toList();
        this.byType = Collections.unmodifiableMap(seen);
    }

    public Optional<TrashProvider> find(String type) {
        return Optional.ofNullable(byType.get(type));
    }

    /** In purge order. */
    public List<TrashProvider> ranked() {
        return ranked;
    }

    /** The provided types, in purge order. */
    public List<String> types() {
        return ranked.stream().map(TrashProvider::type).toList();
    }

    /** Type to purge rank, in purge order. */
    public Map<String, Integer> rankByType() {
        Map<String, Integer> ranks = new LinkedHashMap<>();
        ranked.forEach(provider -> ranks.put(provider.type(), provider.purgeRank()));
        return Collections.unmodifiableMap(ranks);
    }

    /** The types that opted in to a reminder before expiry, in purge order. */
    public List<String> remindingTypes() {
        return ranked.stream().filter(TrashProvider::remindsBeforeExpiry).map(TrashProvider::type).toList();
    }
}
