package com.flowcortex.content.media;

import com.flowcortex.content.web.ContentProblemException;
import com.flowcortex.content.web.ContentProblemException.MediaRejection;
import com.flowcortex.media.api.MediaDownload;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.media.api.StoredContentMedia;
import com.flowcortex.media.api.UploadGrant;
import com.flowcortex.media.api.UploadPurpose;
import com.flowcortex.media.api.UploadRejectedException;
import com.flowcortex.shared.web.PageView;
import com.flowcortex.user.api.UserDirectory;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The content media library: grants an upload, keeps the uploaded file at {@code content/<id>} of the media
 * bucket and records the library item, lists the items and signs the public download of one. Never
 * transactional as a whole: keeping a copy can take minutes and must not hold a database connection.
 */
@Service
public class ContentMediaService {

    /** One library item with its uploader resolved. */
    public record MediaItem(UUID id, String kind, String contentType, long sizeBytes, String fileName,
            String uploaderEmail, Instant createdAt) {
    }

    private static final Logger LOG = LoggerFactory.getLogger(ContentMediaService.class);
    private static final Map<String, String> FILE_EXTENSION_OF_TYPE = Map.of(
            "image/png", "png", "image/jpeg", "jpg", "image/webp", "webp", "video/mp4", "mp4", "video/webm", "webm");

    private final MediaJobs mediaJobs;
    private final ContentMediaRepository media;
    private final UserDirectory users;
    private final Clock clock;
    private final TransactionTemplate transaction;

    ContentMediaService(MediaJobs mediaJobs, ContentMediaRepository media, UserDirectory users, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.mediaJobs = mediaJobs;
        this.media = media;
        this.users = users;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * A signed upload for {@code fileName} of {@code size} bytes. The name only picks the type; it is not stored
     * until the upload is registered.
     */
    public UploadGrant grant(UUID callerId, String fileName, long size) {
        requireMediaOn();
        try {
            return mediaJobs.prepareUpload(UploadPurpose.CONTENT_MEDIA, callerId, fileName, size);
        } catch (UploadRejectedException e) {
            throw rejected("grant", e);
        }
    }

    /**
     * Keeps the caller's upload as a new library item named {@code fileName}. When the row cannot be saved the
     * kept copy is handed back to the store's expiry and the failure is rethrown.
     */
    public MediaItem register(UUID callerId, UUID uploadId, String fileName) {
        requireMediaOn();
        UUID id = UUID.randomUUID();
        StoredContentMedia stored;
        try {
            stored = mediaJobs.keepContentMedia(callerId, uploadId, id);
        } catch (UploadRejectedException e) {
            throw rejected("register", e);
        }
        ContentMedia item = new ContentMedia(id, stored, fileName.strip(), callerId, clock.instant());
        try {
            transaction.executeWithoutResult(status -> media.save(item));
        } catch (RuntimeException e) {
            release(stored);
            throw e;
        }
        LOG.info("content.media outcome=registered id={} kind={} bytes={}", id, stored.kind(), stored.sizeBytes());
        return items(List.of(item)).getFirst();
    }

    /** A page of library items, newest first, optionally only of {@code kind} ({@code image} or {@code video}). */
    public PageView<MediaItem> page(String kind, int page, int size) {
        PageRequest request = PageRequest.of(page, size);
        Page<ContentMedia> found = kind == null
                ? media.findAllByOrderByCreatedAtDescIdDesc(request)
                : media.findByKindOrderByCreatedAtDescIdDesc(kind, request);
        return new PageView<>(items(found.getContent()), page, size, found.getTotalElements());
    }

    /** A time-limited download of the item's stored copy. */
    public MediaDownload download(UUID id) {
        ContentMedia item = media.findById(id).orElseThrow(ContentProblemException::mediaNotFound);
        String extension = FILE_EXTENSION_OF_TYPE.getOrDefault(item.contentType(), "bin");
        return mediaJobs.presignedGet(item.bucket(), item.objectKey(), "nodi-media-" + id + "." + extension);
    }

    private List<MediaItem> items(List<ContentMedia> rows) {
        Map<UUID, String> emails = users.findEmailsByIds(rows.stream().map(ContentMedia::uploadedBy).distinct().toList());
        return rows.stream().map(row -> new MediaItem(row.id(), row.kind(), row.contentType(), row.sizeBytes(),
                row.fileName(), emails.get(row.uploadedBy()), row.createdAt())).toList();
    }

    /** Never throws: it runs on a path that is already failing, and a copy left kept costs storage only. */
    private void release(StoredContentMedia stored) {
        try {
            mediaJobs.releaseContentMedia(stored);
            LOG.info("content.media outcome=released key={}", stored.objectKey());
        } catch (RuntimeException e) {
            LOG.warn("content.media outcome=release_failed key={} error={}", stored.objectKey(),
                    e.getClass().getSimpleName());
        }
    }

    private void requireMediaOn() {
        if (!mediaJobs.isEnabled()) {
            throw ContentProblemException.mediaUnavailable();
        }
    }

    private static ContentProblemException rejected(String step, UploadRejectedException e) {
        LOG.info("content.media outcome=rejected step={} field={} reason={}", step, e.field(), e.detail().code());
        return ContentProblemException.mediaRejected(reasonOf(e.detail().code()));
    }

    /** The library's wording for the media engine's refusal: its type, size, content and pixel refusals, else upload again. */
    private static MediaRejection reasonOf(String engineCode) {
        return switch (engineCode) {
            case "upload.typeOne", "upload.typeMany" -> MediaRejection.TYPE;
            case "upload.sizeMegabytes", "upload.sizeBytes" -> MediaRejection.SIZE;
            case "upload.invalidImage", "upload.invalidVideo" -> MediaRejection.CONTENT;
            case "upload.imageTooLarge" -> MediaRejection.PIXELS;
            default -> MediaRejection.UPLOAD_AGAIN;
        };
    }
}
