package com.flowcortex.connector.api;

import java.time.Instant;

/**
 * One item of a Drive folder; {@code modifiedAt} and {@code sizeBytes} are {@code null} when Google gave none (a Google
 * document has no size).
 */
public record DriveFile(String id, String name, String mimeType, Instant modifiedAt, Long sizeBytes) {
}
