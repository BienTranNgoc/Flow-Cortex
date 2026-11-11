package com.flowcortex.chat.realtime;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Cuts a streamed chunk into pieces whose UTF-8 size fits one event, never inside a character. */
final class DeltaSplitter {

    static final int MAX_BYTES = 4096;

    private DeltaSplitter() {
    }

    /** {@code offset} is the character position of {@code text} in the answer; each piece carries its own. */
    record Piece(int offset, String text) {
    }

    static List<Piece> split(int offset, String text) {
        List<Piece> pieces = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = start;
            int bytes = 0;
            while (end < text.length()) {
                int codePoint = text.codePointAt(end);
                int size = utf8Size(codePoint);
                if (bytes + size > MAX_BYTES) {
                    break;
                }
                bytes += size;
                end += Character.charCount(codePoint);
            }
            pieces.add(new Piece(offset + start, text.substring(start, end)));
            start = end;
        }
        return pieces;
    }

    private static int utf8Size(int codePoint) {
        return new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
    }
}
