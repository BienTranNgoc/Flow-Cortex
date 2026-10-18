package com.flowcortex.media.application;

import com.flowcortex.media.api.MediaCapability;
import com.flowcortex.media.api.MediaDownload;
import com.flowcortex.media.api.MediaJob;
import com.flowcortex.media.api.MediaJobInput;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.media.api.MediaLimits;
import com.flowcortex.media.api.MediaOutput;
import com.flowcortex.media.api.MediaStoreUnavailableException;
import com.flowcortex.media.api.UploadGrant;
import com.flowcortex.media.api.UploadRejectedException;
import com.flowcortex.media.infrastructure.S3MediaStore;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.exception.SdkException;

@Service
class MediaJobsImpl implements MediaJobs {

    private static final Logger LOG = LoggerFactory.getLogger(MediaJobsImpl.class);
    private static final long MEGABYTE = 1024L * 1024;
    /** The key of the one input a job built from an upload carries. */
    private static final String UPLOAD_INPUT_KEY = "media";
    private static final Pattern PLAIN_FILE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,254}");

    private final ApplicationEventPublisher events;
    private final Validator validator;
    private final MediaProperties properties;
    private final S3MediaStore store;

    MediaJobsImpl(ApplicationEventPublisher events, Validator validator, MediaProperties properties,
            S3MediaStore store) {
        this.events = events;
        this.validator = validator;
        this.properties = properties;
        this.store = store;
    }

    // MANDATORY: outside a transaction the after-commit externalization never fires and the job
    // would be lost silently. noRollbackFor: an invalid job must not doom the caller's
    // transaction, so the caller can still record the failure in it.
    @Override
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = ConstraintViolationException.class)
    public void enqueue(MediaJob job) {
        Set<ConstraintViolation<MediaJob>> violations = validator.validate(job);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
        events.publishEvent(new MediaJobRequested(job));
    }

    @Override
    public boolean isEnabled() {
        return properties.enabled();
    }

    @Override
    public MediaLimits limitsFor(String capability) {
        MediaCapability known = MediaCapability.fromValue(capability);
        return new MediaLimits(properties.queueTimeout(), properties.maxRuntime().get(known.value()),
                properties.upload().maxBytes());
    }

    @Override
    public UploadGrant prepareUpload(UUID ownerId, String fileName, long size) {
        String contentType = allowedType(fileName)
                .orElseThrow(() -> new UploadRejectedException("fileName", typeSentence()));
        if (!withinLimit(size)) {
            throw new UploadRejectedException("size", sizeSentence());
        }
        UUID uploadId = UUID.randomUUID();
        String bucket = properties.s3().uploadBucket();
        String key = objectKey(ownerId, uploadId);
        S3MediaStore.PresignedPut put = store.presignPut(bucket, key, contentType, size, properties.upload().urlTtl());
        LOG.info("Upload grant issued: bucket={} key={} size={} expiresAt={}", bucket, key, size, put.expiresAt());
        return new UploadGrant(uploadId, put.url(), "PUT", put.headers(), put.expiresAt(),
                properties.upload().maxBytes());
    }

    @Override
    public MediaJobInput resolveUpload(UUID ownerId, UUID uploadId) {
        String bucket = properties.s3().uploadBucket();
        String key = objectKey(ownerId, uploadId);
        S3MediaStore.StoredObject stored = store.head(bucket, key)
                .orElseThrow(() -> refused(bucket, key, "no object", "Upload the file again"));
        if (!withinLimit(stored.size())) {
            throw refused(bucket, key, stored.size() + " bytes", sizeSentence());
        }
        if (!properties.upload().mimeTypes().containsValue(stored.contentType())) {
            throw refused(bucket, key, "type " + stored.contentType(), typeSentence());
        }
        return new MediaJobInput(UPLOAD_INPUT_KEY, bucket, key);
    }

    @Override
    public boolean isDownloadable(String bucket) {
        return properties.s3().bucket().equals(bucket);
    }

    @Override
    public MediaDownload presignedGet(String bucket, String key, String fileName) {
        if (!isDownloadable(bucket)) {
            throw new IllegalArgumentException("Only objects in the media bucket are downloadable, not " + bucket);
        }
        // The name goes into Content-Disposition as a quoted-string; anything else would need RFC 6266 encoding.
        if (!PLAIN_FILE_NAME.matcher(fileName).matches()) {
            throw new IllegalArgumentException("Not a plain file name: " + fileName.replaceAll("\\p{Cntrl}", "?"));
        }
        S3MediaStore.PresignedGet get = store.presignGet(bucket, key, "attachment; filename=\"" + fileName + "\"",
                properties.presignTtl());
        LOG.info("Download URL issued: bucket={} key={} expiresAt={}", bucket, key, get.expiresAt());
        return new MediaDownload(get.url(), get.expiresAt());
    }

    @Override
    public MediaOutput putRunObject(UUID runId, int stepOrder, String name, String mime, byte[] bytes) {
        if (!PLAIN_FILE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Not a plain file name: " + name.replaceAll("\\p{Cntrl}", "?"));
        }
        String bucket = properties.s3().uploadBucket();
        String key = MediaJob.outputPrefix(runId, stepOrder) + name;
        try {
            store.put(bucket, key, mime, bytes);
        } catch (SdkException e) {
            // The message may reach a step's error message; where the object was going stays in the log.
            LOG.warn("Run object not stored: bucket={} key={} bytes={}", bucket, key, bytes.length, e);
            throw new MediaStoreUnavailableException("The object store did not take the step's file", e);
        }
        LOG.info("Run object stored: bucket={} key={} bytes={}", bucket, key, bytes.length);
        return new MediaOutput(name, bucket, key, mime, (long) bytes.length, null);
    }

    private static UploadRejectedException refused(String bucket, String key, String reason, String sentence) {
        LOG.info("Upload refused at run start: bucket={} key={} reason={}", bucket, key, reason);
        return new UploadRejectedException("uploadId", sentence);
    }

    private static String objectKey(UUID ownerId, UUID uploadId) {
        return ownerId + "/" + uploadId;
    }

    private Optional<String> allowedType(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) {
            return Optional.empty();
        }
        String extension = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return Optional.ofNullable(properties.upload().mimeTypes().get(extension));
    }

    private boolean withinLimit(long size) {
        return size >= 1 && size <= properties.upload().maxBytes();
    }

    private String sizeSentence() {
        long max = properties.upload().maxBytes();
        String limit = max % MEGABYTE == 0 ? max / MEGABYTE + " MB" : max + " bytes";
        return "Files must be between 1 byte and " + limit + ".";
    }

    private String typeSentence() {
        List<String> extensions = properties.upload().extensions().stream().map(e -> "." + e).toList();
        String allowed = extensions.size() == 1 ? extensions.getFirst()
                : String.join(", ", extensions.subList(0, extensions.size() - 1)) + " or " + extensions.getLast();
        return "This file type can't be checked yet. Use " + allowed + ".";
    }
}
