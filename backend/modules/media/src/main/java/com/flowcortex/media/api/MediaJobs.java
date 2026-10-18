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
     * The same two bounds for a provider-runtime {@code capability}, which is not a media
     * capability and so has no entry in {@link #limitsFor(String)}: {@code generate_video} runs on
     * its own queue with its own bounds, every other provider capability shares one pair.
     */
    MediaLimits providerLimits(String capability);

    /**
     * Signs an upload of {@code size} bytes to a key derived from {@code ownerId} and a new upload id.
     * The type comes from the extension of {@code fileName}, which is not stored anywhere.
     * @throws UploadRejectedException for {@code fileName} outside the allowlist or {@code size} out of range
     */
    UploadGrant prepareUpload(UUID ownerId, String fileName, long size);

    /**
     * The stored object of {@code ownerId}'s upload {@code uploadId}, re-checked and registered as an asset.
     * The key is derived from both ids, so another owner's upload id finds nothing. The input still points
     * at the upload, which its presigned URL can overwrite: copy it with {@link #copyRunInputs}, bind the
     * copy with {@link #bindRunInputs} and hand the worker only the copy.
     * @throws UploadRejectedException on field {@code uploadId} if the object is missing, too large or not one of
     *         the audio/video types
     */
    MediaAssetInput resolveUpload(UUID ownerId, UUID uploadId);

    /** Resolves and registers 1-10 uploads, preserving their order as {@code image_1..image_N}. */
    List<MediaAssetInput> resolveImageUploads(UUID ownerId, List<UUID> uploadIds);

    /**
     * Copies already owner-verified assets into {@code runs/<runId>/inputs/<bindingKey>} of the media bucket,
     * for a run that does not exist yet: call it outside any transaction, before the run's own opens, since a
     * video copy can take minutes. The uploads bucket expires objects after a day while an approval may wait far
     * longer, and an upload URL can rewrite its object, so the worker reads only the copy. With the content check
     * on, the first bytes of each copy must match its kind. Copies of a start that fails later are not deleted;
     * the media bucket's run-input lifecycle rule expires them.
     * @return the inputs, each pointing at its copy in the media bucket
     * @throws UploadRejectedException on field {@code uploadId} for the single media upload, {@code uploadIds}
     *         otherwise, if an upload vanished before it was copied; on field {@code uploadId} if a copy's
     *         content is not its kind
     * @throws org.springframework.transaction.IllegalTransactionStateException if a transaction is active
     */
    List<MediaAssetInput> copyRunInputs(UUID runId, List<MediaAssetInput> inputs);

    /**
     * Snapshots the copies {@link #copyRunInputs} made for {@code runId} against that run, in the caller's
     * transaction, once the run is saved.
     * @throws IllegalArgumentException if an input is not a copy under {@code runs/<runId>/inputs/} of the media bucket
     */
    void bindRunInputs(UUID runId, List<MediaAssetInput> copies);

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
