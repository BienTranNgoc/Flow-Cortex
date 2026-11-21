package com.flowcortex.chat.cards;

import java.util.UUID;

/** An uploaded image attached to a message or listed on a card, with the name the member sees. */
public record Attachment(UUID uploadId, String fileName) {

    public static final int MAX_FILE_NAME_CHARS = 255;
}
