package com.flowcortex.entity.domain;

import com.flowcortex.entity.domain.EntityChanges.Change;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * What restoring an older version writes: the snapshot to store as the next version, the parts of the older version
 * whose asset is no longer in the space (so cannot come back), and the change list of the new version.
 */
public record RestorePlan(EntitySnapshot next, List<EntitySnapshot.Part> dropped, List<Change> changes) {

    /**
     * The target's content with its parts limited to {@code assetsInSpace}, kept parts renumbered from 0 in the
     * target's order, and the single change {@code restored} naming {@code fromVersion}.
     */
    public static RestorePlan of(EntitySnapshot target, Set<UUID> assetsInSpace, int fromVersion) {
        List<EntitySnapshot.Part> kept = new ArrayList<>();
        List<EntitySnapshot.Part> dropped = new ArrayList<>();
        List<EntitySnapshot.Part> inOrder = target.parts().stream()
                .sorted(Comparator.comparingInt(EntitySnapshot.Part::sort))
                .toList();
        for (EntitySnapshot.Part part : inOrder) {
            if (assetsInSpace.contains(part.assetId())) {
                kept.add(new EntitySnapshot.Part(part.assetId(), part.role(), part.label(), kept.size()));
            } else {
                dropped.add(part);
            }
        }
        return new RestorePlan(target.withParts(kept), List.copyOf(dropped), List.of(Change.restored(fromVersion)));
    }
}
