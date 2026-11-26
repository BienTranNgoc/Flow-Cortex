package com.flowcortex.trend.taxonomy;

import java.util.Map;
import java.util.UUID;

/**
 * A source of how many templates reference each category and tag. Every registered source SHALL count
 * references disjoint from every other source (one source per referencing table or column), because the
 * taxonomy sums them. Public so template code in another sub-package can implement it; deliberately without
 * a named interface, so it stays internal to the trend module.
 */
public interface TaxonomyUsage {

    /** Count per category key; a key with no reference may be absent. */
    Map<String, Integer> categoryUsage();

    /** Count per tag id; an id with no reference may be absent. */
    Map<UUID, Integer> tagUsage();

    /** Count for one category key, 0 when unreferenced. */
    int categoryUsage(String key);
}
