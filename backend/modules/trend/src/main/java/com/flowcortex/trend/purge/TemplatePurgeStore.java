package com.flowcortex.trend.purge;

import java.time.Instant;
import java.util.List;

/**
 * The persistence side of purging a template, over the template table. Exactly one implementation
 * may exist; a second one refuses startup. Without one the purge job does nothing.
 */
public interface TemplatePurgeStore {

    /** At most {@code limit} templates deleted before {@code deletedBefore}, with their filed assets. */
    List<PurgeCandidate> listDue(Instant deletedBefore, int limit);

    /**
     * Takes the template for this pass; false when another runner holds it. A claim older than 30
     * minutes counts as abandoned and is free again.
     */
    boolean claim(String templateId);

    /** Gives the claim back so a later pass can retry the template. */
    void release(String templateId);

    /** Records that the asset is deleted, so a retry does not delete it again. */
    void forgetFiledAsset(String templateId, String assetId);

    /** Deletes the template row; false when nothing was deleted. */
    boolean deleteRow(String templateId);
}
