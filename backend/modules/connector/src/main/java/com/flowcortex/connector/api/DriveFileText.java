package com.flowcortex.connector.api;

/**
 * The text of one Drive file. {@code detectedType} is one of {@code gdoc, pdf, docx, text, image, media, gsuite,
 * unsupported}; {@code warning} is one of {@code tooLarge, unreadable, encrypted, media, unsupported,
 * googleFileType} or {@code null}. A file that was not or could not be read has empty text.
 */
public record DriveFileText(String text, String detectedType, boolean truncated, boolean needsVision,
        String warning) {
}
