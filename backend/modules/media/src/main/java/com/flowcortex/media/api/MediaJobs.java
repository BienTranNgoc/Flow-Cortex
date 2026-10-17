package com.flowcortex.media.api;

import com.flowcortex.media.api.provider.ProviderJob;
import java.util.UUID;
import java.util.List;

/** Port of the media engine for the modules that dispatch remote work. */
public interface MediaJobs {

    /**
     * Validates {@code job} and records it for publication in the caller's transaction, so the job
     * reaches the broker only if that transaction commits.
     * @throws jakarta.validation.ConstraintViolationException if {@code job} breaks the contract; nothing is recorded
     * @throws org.springframework.transaction.IllegalTransactionStateException if no transaction is active
     */
    void enqueue(MediaJob job);

    /** Validates and records a provider job in the caller's transaction. */
    void enqueueProvider(ProviderJob job);

    /** {@code flowcortex.media.enabled}: whether remote steps and uploads are accepted. */
    boolean isEnabled();

    /** @throws IllegalArgumentException if {@code capability} is not a known capability */
    MediaLimits limitsFor(String capability);

    /**
     * The same two bounds for the provider runtime (script and speech), which is not a media
     * capability and so has no entry in {@link #limitsFor(String)}.
     */
    MediaLimits providerLimits();

    /**
     * Signs an upload of {@code size} bytes to a key derived from {@code ownerId} and a new upload id.
     * The type comes from the extension of {@code fileName}, which is not stored anywhere.
     * @throws UploadRejectedException for {@code fileName} outside the allowlist or {@code size} out of range
     */
    UploadGrant prepareUpload(UUID ownerId, String fileName, long size);

    /**
     * The stored object of {@code ownerId}'s upload {@code uploadId}, re-checked, as a job input.
     * The key is derived from both ids, so another owner's upload id finds nothing.
     * @throws UploadRejectedException on field {@code uploadId} if the object is missing, too large or not one of
     *         the audio/video types
     */
    MediaJobInput resolveUpload(UUID ownerId, UUID uploadId);

    /** Resolves and registers 1-10 uploads, preserving their order as {@code image_1..image_N}. */
    List<MediaAssetInput> resolveImageUploads(UUID ownerId, List<UUID> uploadIds);

    /**
     * Snapshots already owner-verified assets against a run in the caller's transaction, copying each
     * object into {@code runs/<runId>/inputs/<bindingKey>} of the media bucket first. The uploads bucket
     * expires objects after a day while an approval may wait far longer, so only the copy is bound.
     * @return the bound inputs, each pointing at its copy in the media bucket
     * @throws UploadRejectedException on field {@code uploadIds} if an upload vanished before it was copied
     */
    List<MediaAssetInput> bindRunInputs(UUID runId, List<MediaAssetInput> inputs);

    /**
     * Registers worker outputs accepted for the current step attempt. The caller must invoke this only after
     * winning the workflow completion CAS and inside the same transaction.
     */
    void registerOutputs(UUID ownerId, UUID runId, UUID runStepId, int stepOrder, short attempt,
            List<MediaOutput> outputs);

    /**
     * A time-limited GET of the stored output {@code bucket}/{@code key} that the browser saves as
     * {@code fileName}. Only objects in the media bucket are ever signed for download.
     * @throws IllegalArgumentException if {@code bucket} is not the media bucket, or {@code fileName} is not a
     *         plain server-chosen file name
     */
    MediaDownload presignedGet(String bucket, String key, String fileName);
}
