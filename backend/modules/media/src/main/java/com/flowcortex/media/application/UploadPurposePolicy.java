package com.flowcortex.media.application;

import com.flowcortex.media.api.UploadPurpose;
import com.flowcortex.media.api.UploadRejectedException;
import com.flowcortex.shared.web.LocalizedMessage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The only arbiter of what an upload may be for each {@link UploadPurpose}: accepted extensions, per-kind
 * size cap and object-key prefix. Pure: it reads nothing but the configured run-input limits.
 */
final class UploadPurposePolicy {

    static final String SUPPORT_ATTACHMENT_PREFIX = "support-attachment/";
    static final String REVIEW_IMAGE_PREFIX = "review-image/";

    private static final long MEGABYTE = 1024L * 1024;
    /** Insertion order is the order formats are listed to the member. */
    private static final Map<String, String> IMAGE_MIME_TYPES = imageMimeTypes();

    /** What a granted upload may be: its canonical content type, kind and the byte cap of that kind. */
    record Allowed(String contentType, String kind, long maxBytes) {
    }

    private final MediaProperties.Upload upload;

    UploadPurposePolicy(MediaProperties.Upload upload) {
        this.upload = upload;
    }

    private static Map<String, String> imageMimeTypes() {
        Map<String, String> types = new LinkedHashMap<>();
        types.put("png", "image/png");
        types.put("jpg", "image/jpeg");
        types.put("jpeg", "image/jpeg");
        types.put("webp", "image/webp");
        return Collections.unmodifiableMap(types);
    }

    /**
     * @throws UploadRejectedException on field {@code fileName} for an extension outside the purpose's table, on
     *         field {@code size} for a size below one byte or over the cap of the extension's kind
     */
    Allowed grant(UploadPurpose purpose, String fileName, long size) {
        String contentType = mimeTypes(purpose).get(extensionOf(fileName));
        if (contentType == null) {
            throw new UploadRejectedException("fileName", typeDetail(purpose));
        }
        String kind = MediaProperties.Upload.kindOf(contentType);
        long cap = maxBytes(purpose, kind);
        if (size < 1 || size > cap) {
            throw new UploadRejectedException("size", sizeDetail(purpose, kind, cap));
        }
        return new Allowed(contentType, kind, cap);
    }

    String objectKey(UploadPurpose purpose, UUID ownerId, UUID uploadId) {
        String prefix = switch (purpose) {
            case RUN_INPUT -> "";
            case SUPPORT_ATTACHMENT -> SUPPORT_ATTACHMENT_PREFIX;
            case REVIEW_IMAGE -> REVIEW_IMAGE_PREFIX;
        };
        return prefix + ownerId + "/" + uploadId;
    }

    /** The size cap of {@code kind} under {@code purpose}, or 0 for a kind the purpose never accepts. */
    long maxBytes(UploadPurpose purpose, String kind) {
        return switch (purpose) {
            case RUN_INPUT -> upload.maxBytesOf(kind);
            case SUPPORT_ATTACHMENT -> "image".equals(kind) ? UploadPurpose.SUPPORT_ATTACHMENT_MAX_BYTES : 0L;
            case REVIEW_IMAGE -> "image".equals(kind) ? UploadPurpose.REVIEW_IMAGE_MAX_BYTES : 0L;
        };
    }

    /** The refusal for a stored object of {@code kind} that is empty or over the purpose's cap for that kind. */
    LocalizedMessage sizeDetail(UploadPurpose purpose, String kind) {
        return sizeDetail(purpose, kind, maxBytes(purpose, kind));
    }

    /** Extension to canonical content type, in the order formats are listed to the member. */
    Map<String, String> mimeTypes(UploadPurpose purpose) {
        return switch (purpose) {
            case RUN_INPUT -> upload.mimeTypes();
            case SUPPORT_ATTACHMENT, REVIEW_IMAGE -> IMAGE_MIME_TYPES;
        };
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private LocalizedMessage typeDetail(UploadPurpose purpose) {
        List<String> extensions = new ArrayList<>();
        switch (purpose) {
            case RUN_INPUT -> {
                extensions.addAll(upload.mediaExtensions());
                extensions.addAll(upload.productImageExtensions());
            }
            case SUPPORT_ATTACHMENT, REVIEW_IMAGE -> extensions.addAll(IMAGE_MIME_TYPES.keySet());
        }
        List<String> dotted = extensions.stream().map(e -> "." + e).toList();
        return dotted.size() == 1
                ? new LocalizedMessage("upload.typeOne", dotted.getFirst())
                : new LocalizedMessage("upload.typeMany",
                        String.join(", ", dotted.subList(0, dotted.size() - 1)), dotted.getLast());
    }

    private LocalizedMessage sizeDetail(UploadPurpose purpose, String kind, long cap) {
        List<String> extensions = switch (purpose) {
            case RUN_INPUT -> upload.extensionsOf(kind);
            case SUPPORT_ATTACHMENT, REVIEW_IMAGE -> List.copyOf(IMAGE_MIME_TYPES.keySet());
        };
        String formats = String.join(", ", extensions.stream().map(e -> "." + e).toList());
        return cap % MEGABYTE == 0
                ? new LocalizedMessage("upload.sizeMegabytes", cap / MEGABYTE, formats)
                : new LocalizedMessage("upload.sizeBytes", cap, formats);
    }
}
