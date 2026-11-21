package com.flowcortex.media.api;

/** What an upload is granted for; it decides the accepted formats, the size caps and the object-key prefix. */
public enum UploadPurpose {
    /** A file a workflow run takes as input (video, audio or product image). */
    RUN_INPUT,
    /** An image attached to a support request or message. */
    SUPPORT_ATTACHMENT;

    /** Size cap of one support-attachment image; the purpose table and the support module both read it here. */
    public static final long SUPPORT_ATTACHMENT_MAX_BYTES = 5L * 1024 * 1024;
}
