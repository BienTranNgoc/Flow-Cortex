package com.flowcortex.media.infrastructure;

import java.io.FilterInputStream;
import java.io.IOException;
import software.amazon.awssdk.core.ResponseInputStream;

/**
 * An object body that aborts the HTTP connection when closed before its end. A plain close of a
 * {@link ResponseInputStream} on the Apache client drains the rest of the object to reuse the
 * connection, which costs the whole object when a reader stops early.
 */
final class AbortingOnEarlyClose extends FilterInputStream {

    private final ResponseInputStream<?> body;
    private boolean ended;

    AbortingOnEarlyClose(ResponseInputStream<?> body) {
        super(body);
        this.body = body;
    }

    @Override
    public int read() throws IOException {
        int value = super.read();
        if (value < 0) {
            ended = true;
        }
        return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        int count = super.read(buffer, offset, length);
        if (count < 0) {
            ended = true;
        }
        return count;
    }

    @Override
    public void close() throws IOException {
        if (ended) {
            body.close();
        } else {
            body.abort();
        }
    }
}
