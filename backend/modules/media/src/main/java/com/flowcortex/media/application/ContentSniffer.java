package com.flowcortex.media.application;

import java.nio.charset.StandardCharsets;

/**
 * Whether a file's first bytes carry a signature of the media kind its extension claims. A prefix
 * shorter than a signature never matches it.
 */
final class ContentSniffer {

    /** How many leading bytes to read from the store; every signature fits in them. */
    static final int PREFIX_LENGTH = 16;

    private static final byte[] FTYP = ascii("ftyp");
    private static final byte[] EBML = {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3};
    private static final byte[] ID3 = ascii("ID3");
    private static final byte[] RIFF = ascii("RIFF");
    private static final byte[] WAVE = ascii("WAVE");
    private static final byte[] WEBP = ascii("WEBP");
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

    private ContentSniffer() {
    }

    static boolean matches(String kind, byte[] prefix) {
        return switch (kind) {
            case "video" -> isoMedia(prefix) || at(prefix, 0, EBML);
            case "audio" -> isoMedia(prefix) || at(prefix, 0, ID3) || mpegFrameSync(prefix)
                    || at(prefix, 0, RIFF) && at(prefix, 8, WAVE);
            case "image" -> at(prefix, 0, PNG) || at(prefix, 0, JPEG) || at(prefix, 0, RIFF) && at(prefix, 8, WEBP);
            default -> false;
        };
    }

    /** MP4, MOV and M4A all open with an ISO base media {@code ftyp} box. */
    private static boolean isoMedia(byte[] prefix) {
        return at(prefix, 4, FTYP);
    }

    /** An MPEG audio frame header starts with 11 set bits: {@code FF Ex} or {@code FF Fx}. */
    private static boolean mpegFrameSync(byte[] prefix) {
        return prefix.length >= 2 && prefix[0] == (byte) 0xFF && (prefix[1] & 0xE0) == 0xE0;
    }

    private static boolean at(byte[] prefix, int offset, byte[] signature) {
        if (prefix.length < offset + signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (prefix[offset + i] != signature[i]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}
