package com.flowcortex.app.media;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/** Smallest well-formed PNG, JPEG and WebP bodies: headers the image hardening step parses, no metadata. */
public final class ImageFixtures {

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    private ImageFixtures() {
    }

    public static byte[] png() {
        return png(40, 30);
    }

    /** Header-only dimensions: the pixel data is a few bytes however large the declared size. */
    public static byte[] png(long width, long height) {
        return join(PNG_SIGNATURE,
                pngChunk("IHDR", join(bigEndian(width, 4), bigEndian(height, 4), new byte[] {8, 2, 0, 0, 0})),
                pngChunk("IDAT", new byte[] {1, 2, 3}),
                pngChunk("IEND", new byte[0]));
    }

    public static byte[] jpeg() {
        byte[] sof = join(new byte[] {(byte) 0xFF, (byte) 0xC0}, bigEndian(11, 2), new byte[] {8},
                bigEndian(30, 2), bigEndian(40, 2), new byte[] {1, 1, 0x11, 0});
        byte[] sos = join(new byte[] {(byte) 0xFF, (byte) 0xDA}, bigEndian(8, 2),
                new byte[] {1, 1, 0, 0, 63, 0}, new byte[] {0x12, 0x34});
        return join(new byte[] {(byte) 0xFF, (byte) 0xD8}, sof, sos, new byte[] {(byte) 0xFF, (byte) 0xD9});
    }

    public static byte[] webp() {
        int width = 40;
        int height = 30;
        long bits = (width - 1) | ((long) (height - 1) << 14);
        byte[] chunk = join(ascii("VP8L"), littleEndian(8, 4), new byte[] {0x2F}, littleEndian(bits, 4),
                new byte[] {1, 2, 3});
        return join(ascii("RIFF"), littleEndian(chunk.length + 4L, 4), ascii("WEBP"), chunk);
    }

    private static byte[] pngChunk(String type, byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(ascii(type));
        crc.update(data);
        return join(bigEndian(data.length, 4), ascii(type), data, bigEndian(crc.getValue(), 4));
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] bigEndian(long value, int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[length - 1 - i] = (byte) (value >> (8 * i));
        }
        return bytes;
    }

    private static byte[] littleEndian(long value, int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (value >> (8 * i));
        }
        return bytes;
    }

    private static byte[] join(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
