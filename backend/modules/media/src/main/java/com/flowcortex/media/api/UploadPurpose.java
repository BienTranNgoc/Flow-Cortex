package com.flowcortex.media.api;

/** What an upload is granted for; it decides the accepted formats, the size caps and the object-key prefix. */
public enum UploadPurpose {
    /** A file a workflow run takes as input (video, audio or product image). */
    RUN_INPUT,
    /** An image attached to a support request or message. */
    SUPPORT_ATTACHMENT,
    /** A video, audio or image file a member keeps in the Drive library; same formats and caps as a run input. */
    DRIVE_ITEM,
    /** A member's avatar or cover picture; only the owner ever sees it. */
    PROFILE_IMAGE,
    /** An image attached to a workflow review. */
    REVIEW_IMAGE,
    /** An image or video staff upload to the content media library (PNG, JPEG, WebP, MP4, WebM). */
    CONTENT_MEDIA;

    /** Size cap of one support-attachment image; the purpose table and the support module both read it here. */
    public static final long SUPPORT_ATTACHMENT_MAX_BYTES = 5L * 1024 * 1024;

    /** Size cap of one review image; the purpose table and the workflow module both read it here. */
    public static final long REVIEW_IMAGE_MAX_BYTES = 5L * 1024 * 1024;
}
