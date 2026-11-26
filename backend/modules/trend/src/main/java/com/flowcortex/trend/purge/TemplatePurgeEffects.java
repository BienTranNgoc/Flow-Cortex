package com.flowcortex.trend.purge;

/**
 * The storage side of purging a template: its media objects and the external assets filed on it.
 * Exactly one implementation may exist; a second one refuses startup. Without one the purge job does
 * nothing. Either method may throw; an exception implementing {@link TemplatePurgeFailure} supplies
 * the code that is logged.
 */
public interface TemplatePurgeEffects {

    /** Deletes every stored object of the template and answers how many were deleted; idempotent. */
    int deleteMedia(String templateId);

    /** Deletes one external asset; deleting an asset that is already gone is not a failure. */
    void deleteFiledAsset(String assetId);
}
