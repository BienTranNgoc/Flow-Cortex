package com.flowcortex.support.request;

import com.flowcortex.media.api.MediaAssetInput;
import com.flowcortex.media.api.MediaDownload;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.media.api.StoredAttachment;
import com.flowcortex.media.api.UploadGrant;
import com.flowcortex.media.api.UploadRejectedException;
import com.flowcortex.support.web.SupportProblemException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Images on request messages: PNG, JPG or WebP, at most 5 MB and 3 per message. The grant checks the name and
 * size; on submit the upload is re-checked by the media engine and its copy sniffed before it is kept.
 */
@Service
class AttachmentService {

    static final long MAX_BYTES = 5L * 1024 * 1024;
    static final int MAX_PER_MESSAGE = 3;
    static final String REJECTED = "attachment-rejected";

    private static final Logger LOG = LoggerFactory.getLogger(AttachmentService.class);
    private static final Map<String, String> EXTENSIONS = Map.of(
            "png", "png", "jpg", "jpg", "jpeg", "jpg", "webp", "webp");
    private static final Map<String, String> FILE_EXTENSION_OF_TYPE = Map.of(
            "image/png", "png", "image/jpeg", "jpg", "image/webp", "webp");

    private final MediaJobs mediaJobs;

    AttachmentService(MediaJobs mediaJobs) {
        this.mediaJobs = mediaJobs;
    }

    UploadGrant grant(UUID ownerId, String fileName, long size) {
        requireMediaOn();
        int dot = fileName.lastIndexOf('.');
        String extension = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!EXTENSIONS.containsKey(extension)) {
            throw rejected("Chỉ nhận ảnh PNG, JPG hoặc WebP");
        }
        if (size < 1 || size > MAX_BYTES) {
            throw rejected("Ảnh tối đa 5 MB");
        }
        try {
            return mediaJobs.prepareUpload(ownerId, fileName, size);
        } catch (UploadRejectedException e) {
            throw rejected("Chỉ nhận ảnh PNG, JPG hoặc WebP");
        }
    }

    /** Checks the count before anything is resolved, so a fourth image costs no store call. */
    static List<UUID> checkCount(List<UUID> uploadIds) {
        List<UUID> ids = uploadIds == null ? List.of() : uploadIds;
        if (ids.size() > MAX_PER_MESSAGE) {
            throw rejected("Tối đa 3 ảnh cho mỗi tin nhắn");
        }
        if (ids.stream().anyMatch(java.util.Objects::isNull) || ids.stream().distinct().count() != ids.size()) {
            throw rejected("Mỗi ảnh chỉ đính kèm một lần");
        }
        return ids;
    }

    /**
     * Copies the uploads to {@code support/<requestId>/<messageId>/<n>} and keeps them. Runs outside any
     * transaction: the copies take store round trips, and a refused one must not hold a database connection.
     */
    List<StoredAttachment> keep(UUID ownerId, UUID requestId, UUID messageId, List<UUID> uploadIds) {
        if (uploadIds.isEmpty()) {
            return List.of();
        }
        requireMediaOn();
        try {
            List<MediaAssetInput> inputs = mediaJobs.resolveImageUploads(ownerId, uploadIds);
            return mediaJobs.keepAttachments("support/" + requestId + "/" + messageId, inputs, MAX_BYTES);
        } catch (UploadRejectedException e) {
            LOG.info("support.attachment outcome=rejected field={}", e.field());
            throw rejected("Tệp không phải ảnh hợp lệ");
        }
    }

    /**
     * Hands kept copies that no saved message references back to the store's expiry. Never throws: it runs on a
     * path that is already answering, and a copy left kept costs storage only.
     */
    void release(List<StoredAttachment> kept) {
        if (kept.isEmpty()) {
            return;
        }
        try {
            mediaJobs.releaseAttachments(kept);
            LOG.info("support.attachment outcome=released count={}", kept.size());
        } catch (RuntimeException e) {
            LOG.warn("support.attachment outcome=release_failed count={} error={}", kept.size(),
                    e.getClass().getSimpleName());
        }
    }

    MediaDownload download(String bucket, String objectKey, long number, int position, String contentType) {
        String extension = FILE_EXTENSION_OF_TYPE.getOrDefault(contentType, "img");
        return mediaJobs.presignedGet(bucket, objectKey, "yeu-cau-" + number + "-anh-" + position + "." + extension);
    }

    private void requireMediaOn() {
        if (!mediaJobs.isEnabled()) {
            throw new SupportProblemException(HttpStatus.SERVICE_UNAVAILABLE, "attachments-unavailable",
                    "Tạm thời chưa nhận ảnh đính kèm, bạn vẫn gửi được nội dung.");
        }
    }

    private static SupportProblemException rejected(String detail) {
        return new SupportProblemException(HttpStatus.BAD_REQUEST, REJECTED, detail);
    }
}
