package com.flowcortex.connector.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BoundedReadTest {

    /** An endless stream that counts the bytes handed out. */
    private static final class Endless extends InputStream {

        final AtomicInteger served = new AtomicInteger();

        @Override
        public int read() {
            served.incrementAndGet();
            return 'x';
        }

        @Override
        public int read(byte[] target, int offset, int length) {
            served.addAndGet(length);
            java.util.Arrays.fill(target, offset, offset + length, (byte) 'x');
            return length;
        }
    }

    @Test
    void aStreamOfExactlyTheLimitIsReadWholeAndIsNotOver() throws IOException {
        byte[] data = new byte[100];

        BoundedRead.Result result = BoundedRead.read(new ByteArrayInputStream(data), 100);

        assertThat(result.bytes()).hasSize(100);
        assertThat(result.overLimit()).isFalse();
    }

    @Test
    void aLongerStreamIsCutAtTheLimitAndReadNoFurtherThanOneByteBeyondIt() throws IOException {
        Endless stream = new Endless();

        BoundedRead.Result result = BoundedRead.read(stream, 100);

        assertThat(result.overLimit()).isTrue();
        assertThat(result.bytes()).hasSize(100);
        assertThat(stream.served.get()).isLessThanOrEqualTo(101);
    }

    @Test
    void anEmptyStreamGivesNoBytes() throws IOException {
        BoundedRead.Result result = BoundedRead.read(new ByteArrayInputStream(new byte[0]), 100);

        assertThat(result.bytes()).isEmpty();
        assertThat(result.overLimit()).isFalse();
    }
}
