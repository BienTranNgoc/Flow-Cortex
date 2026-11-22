package com.flowcortex.workflow.application;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

/** The waveform of a sound: the loudest sample of each of {@value #SLOTS} equal slots, scaled to the loudest slot. */
final class AudioPeaks {

    static final int SLOTS = 120;
    private static final int FRAMES_PER_READ = 4096;

    private AudioPeaks() {
    }

    /**
     * Decodes the WAV read from {@code in}, looking at no more than the first {@code maxSeconds} seconds, in one
     * pass with constant memory. The stream is buffered here because the object store's stream cannot mark and
     * reset, which the JDK's file readers need. The caller closes {@code in}.
     */
    static PeaksOutcome compute(InputStream in, long maxSeconds) {
        AudioInputStream audio;
        try {
            audio = AudioSystem.getAudioInputStream(new BufferedInputStream(in));
        } catch (UnsupportedAudioFileException | IOException | RuntimeException e) {
            // Nothing of the sample body has been read yet, so whatever failed here is the file's header.
            return new PeaksOutcome.NotDecodable();
        }
        return decode(audio, maxSeconds);
    }

    static PeaksOutcome decode(AudioInputStream audio, long maxSeconds) {
        AudioFormat format = audio.getFormat();
        long frameLength = audio.getFrameLength();
        if (frameLength <= 0 || format.getSampleRate() <= 0 || format.getChannels() <= 0) {
            return new PeaksOutcome.NotDecodable();
        }
        int channels = format.getChannels();
        int sampleBytes = format.getSampleSizeInBits() / 8;
        if (format.getFrameSize() != channels * sampleBytes || !supported(format, sampleBytes)) {
            return new PeaksOutcome.NotDecodable();
        }
        long total = Math.min(frameLength, (long) (maxSeconds * (double) format.getSampleRate()));
        if (total <= 0) {
            return new PeaksOutcome.NotDecodable();
        }
        float[] slots = new float[SLOTS];
        byte[] buffer = new byte[format.getFrameSize() * FRAMES_PER_READ];
        long frame = 0;
        try {
            while (frame < total) {
                int wanted = (int) Math.min(FRAMES_PER_READ, total - frame) * format.getFrameSize();
                int read = audio.read(buffer, 0, wanted);
                if (read < 0) {
                    // The header promised more frames than the file holds.
                    return new PeaksOutcome.NotDecodable();
                }
                int frames = read / format.getFrameSize();
                if (frames == 0) {
                    return new PeaksOutcome.NotDecodable();
                }
                for (int i = 0; i < frames; i++, frame++) {
                    int slot = (int) (frame * SLOTS / total);
                    for (int channel = 0; channel < channels; channel++) {
                        float level = level(buffer, (i * channels + channel) * sampleBytes, sampleBytes, format);
                        if (level > slots[slot]) {
                            slots[slot] = level;
                        }
                    }
                }
            }
        } catch (IOException e) {
            return new PeaksOutcome.StoreUnavailable();
        }
        return new PeaksOutcome.Peaks(normalise(slots));
    }

    private static boolean supported(AudioFormat format, int sampleBytes) {
        AudioFormat.Encoding encoding = format.getEncoding();
        if (encoding == AudioFormat.Encoding.PCM_FLOAT) {
            return sampleBytes == 4 || sampleBytes == 8;
        }
        boolean pcm = encoding == AudioFormat.Encoding.PCM_SIGNED || encoding == AudioFormat.Encoding.PCM_UNSIGNED;
        return pcm && sampleBytes >= 1 && sampleBytes <= 4;
    }

    /** The absolute value of one sample as a fraction of full scale. */
    private static float level(byte[] data, int offset, int sampleBytes, AudioFormat format) {
        boolean bigEndian = format.isBigEndian();
        if (format.getEncoding() == AudioFormat.Encoding.PCM_FLOAT) {
            return floatLevel(data, offset, sampleBytes, bigEndian);
        }
        long value = 0;
        for (int b = 0; b < sampleBytes; b++) {
            int index = bigEndian ? offset + b : offset + sampleBytes - 1 - b;
            value = (value << 8) | (data[index] & 0xff);
        }
        long fullScale = 1L << (sampleBytes * 8 - 1);
        if (format.getEncoding() == AudioFormat.Encoding.PCM_UNSIGNED) {
            value -= fullScale;
        } else if (value >= fullScale) {
            value -= fullScale << 1;
        }
        return Math.min(1f, Math.abs(value) / (float) fullScale);
    }

    private static float floatLevel(byte[] data, int offset, int sampleBytes, boolean bigEndian) {
        long bits = 0;
        for (int b = 0; b < sampleBytes; b++) {
            int index = bigEndian ? offset + b : offset + sampleBytes - 1 - b;
            bits = (bits << 8) | (data[index] & 0xff);
        }
        double value = sampleBytes == 4 ? Float.intBitsToFloat((int) bits) : Double.longBitsToDouble(bits);
        return Double.isNaN(value) ? 0f : (float) Math.min(1d, Math.abs(value));
    }

    private static float[] normalise(float[] slots) {
        float loudest = 0f;
        for (float slot : slots) {
            loudest = Math.max(loudest, slot);
        }
        float[] peaks = new float[slots.length];
        if (loudest == 0f) {
            return peaks;
        }
        for (int i = 0; i < slots.length; i++) {
            peaks[i] = (float) (Math.round(slots[i] / (double) loudest * 1000d) / 1000d);
        }
        return peaks;
    }
}
