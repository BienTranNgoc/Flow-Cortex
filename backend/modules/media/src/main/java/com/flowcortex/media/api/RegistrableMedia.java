package com.flowcortex.media.api;

import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

/** Read and keep operations the asset registry needs over registered media; it never touches the store itself. */
public interface RegistrableMedia {

    /** The files registered for step {@code runStepId}'s {@code attempt}, in object key order. */
    List<StoredMediaFile> stepOutputs(UUID runStepId, short attempt);

    /** The uploads bound to {@code runId} in position order, each pointing at the run's copy. */
    List<BoundRunInput> boundInputs(UUID runId);

    /**
     * Retags a run's input copy so retention no longer expires it.
     * @return the copy's own size in bytes (not the upload's, which a hardened image copy differs from), or
     *         empty when the copy is already gone
     * @throws MediaStoreUnavailableException when the object store fails for any other reason
     * @throws IllegalArgumentException when the key is not {@code runs/<uuid>/inputs/<binding key>} of the media bucket
     */
    OptionalLong keepRunInput(String bucket, String objectKey);
}
