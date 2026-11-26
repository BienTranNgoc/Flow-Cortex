package com.flowcortex.asset.api;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Reads the listable library assets of a space, for modules that attach assets to their own records. */
public interface SpaceAssets {

    /**
     * Returns the listable library assets of the space among {@code assetIds}, in no particular order.
     * An id that is unknown, belongs to another space or is not listable is absent from the answer, so a
     * caller cannot tell the three apart. No ids gives an empty list.
     */
    List<SpaceAssetSummary> findInSpace(UUID spaceId, Collection<UUID> assetIds);
}
