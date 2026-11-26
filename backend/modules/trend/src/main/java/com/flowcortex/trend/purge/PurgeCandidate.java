package com.flowcortex.trend.purge;

import java.util.Objects;
import java.util.Set;

/**
 * A deleted template due for purging: its id and the external assets still filed on it (assets
 * already removed and forgotten are not listed).
 */
public record PurgeCandidate(String templateId, Set<String> filedAssetIds) {

    public PurgeCandidate {
        Objects.requireNonNull(templateId, "templateId");
        filedAssetIds = Set.copyOf(filedAssetIds);
    }
}
