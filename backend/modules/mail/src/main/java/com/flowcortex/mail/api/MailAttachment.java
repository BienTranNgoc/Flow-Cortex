package com.flowcortex.mail.api;

/**
 * A file sent with a mail: its name, its media type and its bytes. The bytes are copied in and out, so a
 * built attachment never changes.
 *
 * @throws IllegalArgumentException when the name or the type is blank or there are no bytes
 */
public record MailAttachment(String fileName, String contentType, byte[] content) {

    public MailAttachment {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("An attachment needs a file name");
        }
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("Attachment " + fileName + " needs a media type");
        }
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("Attachment " + fileName + " has no content");
        }
        content = content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
