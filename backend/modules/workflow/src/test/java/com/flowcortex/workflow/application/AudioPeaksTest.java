package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import org.junit.jupiter.api.Test;

class AudioPeaksTest {

    private static final long ONE_HOUR = 3600;

    /** Little-endian signed PCM of {@code bits} bits per sample, one value per frame per channel. */
    private static byte[] wav(int bits, int channels, float sampleRate, long[][] frames) throws IOException {
        int sampleBytes = bits / 8;
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        for (long[] frame : frames) {
            for (long sample : frame) {
                for (int b = 0; b < sampleBytes; b++) {
                    pcm.write((int) (sample >> (8 * b)) & 0xff);
                }
            }
        }
        AudioFormat format = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, sampleRate, bits, channels,
                channels * sampleBytes, sampleRate, false);
        byte[] data = pcm.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(data), format, frames.length),
                AudioFileFormat.Type.WAVE, out);
        return out.toByteArray();
    }

    private static long[][] mono(int count, long first, int firstCount, long second) {
        long[][] frames = new long[count][];
        for (int i = 0; i < count; i++) {
            frames[i] = new long[] {i < firstCount ? first : second};
        }
        return frames;
    }

    private static PeaksOutcome compute(byte[] bytes) {
        return AudioPeaks.compute(new ByteArrayInputStream(bytes), ONE_HOUR);
    }

    private static float[] peaks(PeaksOutcome outcome) {
        assertThat(outcome).isInstanceOf(PeaksOutcome.Peaks.class);
        return ((PeaksOutcome.Peaks) outcome).values();
    }

    /** A stream that does not support mark and reset, like the object store's response stream. */
    private static final class NoMark extends FilterInputStream {

        NoMark(InputStream in) {
            super(in);
        }

        @Override
        public boolean markSupported() {
            return false;
        }

        @Override
        public synchronized void mark(int readlimit) {
        }

        @Override
        public synchronized void reset() throws IOException {
            throw new IOException("mark/reset not supported");
        }
    }

    @Test
    void loudEndExample() throws IOException {
        float[] values = peaks(compute(wav(16, 1, 8000, mono(1200, 8192, 600, 16384))));

        assertThat(values).hasSize(120);
        assertThat(Arrays.copyOfRange(values, 0, 60)).containsOnly(0.5f);
        assertThat(Arrays.copyOfRange(values, 60, 120)).containsOnly(1.0f);
    }

    @Test
    void stereoTakesTheLargerChannel() throws IOException {
        long[][] frames = new long[240][];
        for (int i = 0; i < frames.length; i++) {
            frames[i] = i < 120 ? new long[] {4096, -16384} : new long[] {-32768, 100};
        }

        float[] values = peaks(compute(wav(16, 2, 8000, frames)));

        assertThat(Arrays.copyOfRange(values, 0, 60)).containsOnly(0.5f);
        assertThat(Arrays.copyOfRange(values, 60, 120)).containsOnly(1.0f);
    }

    @Test
    void silenceIsAllZeros() throws IOException {
        float[] values = peaks(compute(wav(16, 1, 8000, mono(1200, 0, 1200, 0))));

        assertThat(values).hasSize(120).containsOnly(0f);
    }

    @Test
    void eightAndTwentyFourBitAreNormalised() throws IOException {
        float[] eight = peaks(compute(wav(8, 1, 8000, mono(240, 32, 120, 64))));
        float[] twentyFour = peaks(compute(wav(24, 1, 8000, mono(240, 1_000_000, 120, 4_000_000))));

        assertThat(Arrays.copyOfRange(eight, 0, 60)).containsOnly(0.5f);
        assertThat(Arrays.copyOfRange(eight, 60, 120)).containsOnly(1.0f);
        assertThat(Arrays.copyOfRange(twentyFour, 0, 60)).containsOnly(0.25f);
        assertThat(Arrays.copyOfRange(twentyFour, 60, 120)).containsOnly(1.0f);
    }

    @Test
    void valuesAreRoundedToThreeDecimals() throws IOException {
        float[] values = peaks(compute(wav(16, 1, 8000, mono(240, 10000, 120, 30000))));

        assertThat(values[0]).isEqualTo(0.333f);
        assertThat(values[119]).isEqualTo(1.0f);
    }

    @Test
    void readsOnlyTheFirstHour() throws IOException {
        // Ten seconds of 8000 Hz at 8192, then ten seconds at 32767: capped at ten seconds only the first reads.
        float[] values = peaks(AudioPeaks.compute(
                new ByteArrayInputStream(wav(16, 1, 8000, mono(160_000, 8192, 80_000, 32767))), 10));

        assertThat(values).hasSize(120).containsOnly(1.0f);
    }

    @Test
    void notAWavIsNotDecodable() {
        assertThat(compute("this is not a sound".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(PeaksOutcome.NotDecodable.class);
        assertThat(compute(new byte[0])).isInstanceOf(PeaksOutcome.NotDecodable.class);
    }

    @Test
    void noFramesIsNotDecodable() throws IOException {
        assertThat(compute(wav(16, 1, 8000, new long[0][]))).isInstanceOf(PeaksOutcome.NotDecodable.class);
    }

    @Test
    void unknownFrameLengthIsNotDecodable() throws IOException {
        InputStream unknownLength = new InputStream() {
            @Override
            public int read() {
                return -1;
            }
        };
        AudioInputStream stream = new AudioInputStream(unknownLength,
                new AudioFormat(8000f, 16, 1, true, false), AudioSystem.NOT_SPECIFIED);

        assertThat(AudioPeaks.decode(stream, ONE_HOUR)).isInstanceOf(PeaksOutcome.NotDecodable.class);
    }

    @Test
    void decodesThroughAStreamWithoutMarkSupport() throws IOException {
        byte[] bytes = wav(16, 1, 8000, mono(1200, 8192, 600, 16384));

        float[] values = peaks(AudioPeaks.compute(new NoMark(new ByteArrayInputStream(bytes)), ONE_HOUR));

        assertThat(values).hasSize(120);
        assertThat(values[0]).isEqualTo(0.5f);
    }

    @Test
    void aReadFailureMidBodyIsStoreUnavailable() throws IOException {
        byte[] bytes = wav(16, 1, 8000, mono(40_000, 8192, 20_000, 16384));
        InputStream failing = new FilterInputStream(new ByteArrayInputStream(bytes)) {
            private int served;

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (served > 20_000) {
                    throw new IOException("connection reset");
                }
                int read = super.read(b, off, len);
                served += Math.max(read, 0);
                return read;
            }

            @Override
            public int read() throws IOException {
                return super.read();
            }
        };

        assertThat(AudioPeaks.compute(new NoMark(failing), ONE_HOUR)).isInstanceOf(PeaksOutcome.StoreUnavailable.class);
    }
}
