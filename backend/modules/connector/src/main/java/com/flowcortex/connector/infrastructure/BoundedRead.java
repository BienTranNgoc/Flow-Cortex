package com.flowcortex.connector.infrastructure;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/** Reads a stream up to a byte limit, never pulling more than one byte past it. */
final class BoundedRead {

    /** The first {@code max} bytes; {@code overLimit} when the stream held more. */
    record Result(byte[] bytes, boolean overLimit) {
    }

    private BoundedRead() {
    }

    static Result read(InputStream stream, int max) throws IOException {
        byte[] bytes = stream.readNBytes(max + 1);
        boolean overLimit = bytes.length > max;
        return new Result(overLimit ? Arrays.copyOf(bytes, max) : bytes, overLimit);
    }
}
