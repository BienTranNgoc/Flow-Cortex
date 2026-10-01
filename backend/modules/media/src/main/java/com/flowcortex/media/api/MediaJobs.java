package com.flowcortex.media.api;

import java.util.UUID;

/** Port of the media engine for the modules that dispatch remote work. */
public interface MediaJobs {

    /**
     * Validates {@code job} and records it for publication in the caller's transaction, so the job
     * reaches the broker only if that transaction commits.
     * @throws jakarta.validation.ConstraintViolationException if {@code job} breaks the contract; nothing is recorded
     * @throws org.springframework.transaction.IllegalTransactionStateException if no transaction is active
     */
    void enqueue(MediaJob job);

    /** {@code flowcortex.media.enabled}: whether remote steps and uploads are accepted. */
    boolean isEnabled();

    /** @throws IllegalArgumentException if {@code capability} is not a known capability */
    MediaLimits limitsFor(String capability);

    /**
     * Signs an upload of {@code size} bytes to a key derived from {@code ownerId} and a new upload id.
     * The type comes from the extension of {@code fileName}, which is not stored anywhere.
     * @throws UploadRejectedException for {@code fileName} outside the allowlist or {@code size} out of range
     */
    UploadGrant prepareUpload(UUID ownerId, String fileName, long size);

    /**
     * The stored object of {@code ownerId}'s upload {@code uploadId}, re-checked, as a job input.
     * The key is derived from both ids, so another owner's upload id finds nothing.
     * @throws UploadRejectedException on field {@code uploadId} if the object is missing, too large or of another type
     */
    MediaJobInput resolveUpload(UUID ownerId, UUID uploadId);

    /**
     * A time-limited GET of the stored output {@code bucket}/{@code key} that the browser saves as
     * {@code fileName}. Only objects in the media bucket are ever signed for download.
     * @throws IllegalArgumentException if {@code bucket} is not the media bucket, or {@code fileName} is not a
     *         plain server-chosen file name
     */
    MediaDownload presignedGet(String bucket, String key, String fileName);
}
