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
     * Signs an upload of {@code size} bytes to a key derived from {@code purpose}, {@code ownerId} and a new
     * upload id. The type comes from the extension of {@code fileName}, which is not stored anywhere, and must
     * be one the purpose accepts.
     * @throws UploadRejectedException on field {@code fileName} outside the purpose's allowlist, on field
     *         {@code size} out of range
     */
    UploadGrant prepareUpload(UploadPurpose purpose, UUID ownerId, String fileName, long size);

    /**
     * The stored object of {@code ownerId}'s upload {@code uploadId}, re-checked and registered as an asset.
     * The key is derived from both ids, so another owner's upload id finds nothing. The input still points
     * at the upload, which its presigned URL can overwrite: copy it with {@link #copyRunInputs}, bind the
     * copy with {@link #bindRunInputs} and hand the worker only the copy.
     * @throws UploadRejectedException on field {@code uploadId} if the object is missing, too large or not one of
     *         the audio/video types
     */
    MediaAssetInput resolveUpload(UUID ownerId, UUID uploadId);

    /** Whether an output stored in {@code bucket} can be signed for download: only the media bucket's can. */
    boolean isDownloadable(String bucket);

    /**
     * Resolves and registers 1-10 uploads granted for {@code purpose}, preserving their order as
     * {@code image_1..image_N}. An upload granted for another purpose is not found.
     */
    List<MediaAssetInput> resolveImageUploads(UploadPurpose purpose, UUID ownerId, List<UUID> uploadIds);

    /**
     * Copies already owner-verified assets into {@code runs/<runId>/inputs/<bindingKey>} of the media bucket,
     * for a run that does not exist yet: call it outside any transaction, before the run's own opens, since a
     * video copy can take minutes. The uploads bucket expires objects after a day while an approval may wait far
     * longer, and an upload URL can rewrite its object, so the worker reads only the copy. With the content check
     * on, the first bytes of each copy must match its kind. Copies of a start that fails later are not deleted;
     * the media bucket's run-input lifecycle rule expires them. An image is not copied server-side: it is
     * read, checked (format, pixel cap) and written back without its metadata, whatever the content check says.
     * @return the inputs, each pointing at its copy in the media bucket
     * @throws UploadRejectedException on field {@code uploadId} for the single media upload, {@code uploadIds}
     *         otherwise, if an upload vanished before it was copied; on field {@code uploadId} if a copy's
     *         content is not its kind; on field {@code uploadIds} if an image is not the image it was declared
     *         as or has more than 25 million pixels
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
     * Keeps owner-verified image uploads as attachments at {@code <keyPrefix>/1..N} of the media bucket. Each
     * image is read, checked (format, pixel cap) and written without its metadata, tagged pending; all are
     * re-tagged kept only once every copy passed, and a refused copy expires by the media bucket's lifecycle
     * rule. Call it outside any transaction.
     * @return the kept copies, each with the size of the stored (stripped) bytes
     * @throws UploadRejectedException on field {@code uploadIds} if an upload is not an image, is larger than
     *         {@code maxBytes} (checked before any read), vanished, is not the image it was declared as, or has
     *         more than 25 million pixels
     * @throws IllegalArgumentException if {@code keyPrefix} is not under {@code support/}, or there are not 1-10 inputs
     * @throws org.springframework.transaction.IllegalTransactionStateException if a transaction is active
     */
    List<StoredAttachment> keepAttachments(String keyPrefix, List<MediaAssetInput> inputs, long maxBytes);

    /**
     * Re-tags kept attachments pending again, so the media bucket's lifecycle rule expires them: for copies whose
     * request or message was never saved. Tries every object, then rethrows the first failure.
     * @throws IllegalArgumentException if an attachment is not under {@code support/} of the media bucket
     */
    void releaseAttachments(List<StoredAttachment> attachments);

    /**
     * The inputs bound to {@code runId} in start order, each with a signed URL to the run's own copy under
     * {@code runs/<runId>/inputs/}. The caller must have verified that the run belongs to the reader. A run
     * with no bound input yields an empty list.
     */
    List<RunInput> runInputs(UUID runId);

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

    /**
     * Stores {@code bytes} as object {@code name} of step {@code stepOrder} of run {@code runId}, at
     * {@code runs/{runId}/steps/{stepOrder}/{name}} in the uploads bucket — the only bucket the server
     * writes; its lifecycle expires the object, and it is never signed for download. Writing the same
     * step and name again overwrites the object.
     * @return the object as a step records it, like an output the worker wrote
     * @throws IllegalArgumentException if {@code name} is not a plain server-chosen file name; nothing is written
     * @throws MediaStoreUnavailableException if the store does not take the write
     */
    MediaOutput putRunObject(UUID runId, int stepOrder, String name, String mime, byte[] bytes);

    /**
     * Copies {@code source}, an object of run {@code runId} in the media bucket, server-side to object {@code name}
     * of step {@code stepOrder}, kept like a worker output; the result carries the copy's type and size.
     * @throws IllegalArgumentException if {@code source} is outside {@code runs/<runId>/} of the media bucket or
     *         {@code name} is not a plain file name; nothing is copied
     * @throws UploadRejectedException if {@code source} is gone
     * @throws MediaStoreUnavailableException if the store does not take the copy or cannot describe it
     */
    MediaOutput copyRunObject(UUID runId, int stepOrder, MediaJobInput source, String name);
}
