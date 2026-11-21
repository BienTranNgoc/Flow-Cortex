package com.flowcortex.media.application;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Format detection, header dimensions and metadata removal for PNG, JPEG and WebP uploads. Every
 * read is bounds-checked: malformed input yields an empty result and never an exception.
 * {@link #strip} keeps an allowlist of parts byte for byte and drops everything else.
 */
final class ImageHardening {

    static final long MAX_PIXELS = 25_000_000L;

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] JFIF = {'J', 'F', 'I', 'F', 0};
    private static final byte[] ICC_PROFILE = {'I', 'C', 'C', '_', 'P', 'R', 'O', 'F', 'I', 'L', 'E', 0};
    private static final byte[] ADOBE = {'A', 'd', 'o', 'b', 'e'};
    private static final byte[] EXIF = {'E', 'x', 'i', 'f', 0, 0};

    private static final int SOI = 0xD8;
    private static final int EOI = 0xD9;
    private static final int SOS = 0xDA;
    private static final int DQT = 0xDB;
    private static final int DHT = 0xC4;
    private static final int DRI = 0xDD;
    private static final int APP0 = 0xE0;
    private static final int APP1 = 0xE1;
    private static final int APP2 = 0xE2;
    private static final int APP14 = 0xEE;

    private static final int ORIENTATION_TAG = 0x0112;
    private static final int TIFF_SHORT = 3;

    private static final Set<String> PNG_KEPT = Set.of(
            "IHDR", "PLTE", "IDAT", "IEND", "tRNS", "gAMA", "cHRM", "sRGB", "iCCP", "sBIT", "pHYs",
            "acTL", "fcTL", "fdAT");
    private static final Set<String> WEBP_KEPT = Set.of("VP8 ", "VP8L", "VP8X", "ALPH", "ICCP", "ANIM");
    private static final Set<String> WEBP_FRAME_KEPT = Set.of("VP8 ", "VP8L", "ALPH");
    private static final int VP8X_METADATA_FLAGS = 0x08 | 0x04;
    private static final long PNG_MAX_DIMENSION = Integer.MAX_VALUE;

    enum ImageFormat {
        PNG("image/png"),
        JPEG("image/jpeg"),
        WEBP("image/webp");

        private final String mimeType;

        ImageFormat(String mimeType) {
            this.mimeType = mimeType;
        }

        String mimeType() {
            return mimeType;
        }
    }

    record Dimensions(long width, long height) {
        long pixels() {
            return width * height;
        }
    }

    private static final class Malformed extends RuntimeException {
        Malformed() {
            super(null, null, false, false);
        }
    }

    private ImageHardening() {
    }

    static Optional<ImageFormat> detect(byte[] bytes) {
        if (startsWith(bytes, 0, PNG_SIGNATURE)) {
            return Optional.of(ImageFormat.PNG);
        }
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == SOI && (bytes[2] & 0xFF) == 0xFF) {
            return Optional.of(ImageFormat.JPEG);
        }
        if (bytes.length >= 12 && fourCc(bytes, 0).equals("RIFF") && fourCc(bytes, 8).equals("WEBP")) {
            return Optional.of(ImageFormat.WEBP);
        }
        return Optional.empty();
    }

    static Optional<Dimensions> dimensions(ImageFormat format, byte[] bytes) {
        try {
            Dimensions dimensions = switch (format) {
                case PNG -> pngDimensions(bytes);
                case JPEG -> jpegDimensions(bytes);
                case WEBP -> webpDimensions(bytes);
            };
            return dimensions.width() > 0 && dimensions.height() > 0 ? Optional.of(dimensions) : Optional.empty();
        } catch (Malformed e) {
            return Optional.empty();
        }
    }

    static boolean withinPixelCap(Dimensions dimensions) {
        return dimensions.pixels() <= MAX_PIXELS;
    }

    static Optional<byte[]> strip(ImageFormat format, byte[] bytes) {
        try {
            return Optional.of(switch (format) {
                case PNG -> stripPng(bytes);
                case JPEG -> stripJpeg(bytes);
                case WEBP -> stripWebp(bytes);
            });
        } catch (Malformed e) {
            return Optional.empty();
        }
    }

    private static Dimensions pngDimensions(byte[] in) {
        int pos = PNG_SIGNATURE.length;
        if (u32be(in, pos) != 13 || !fourCc(in, pos + 4).equals("IHDR")) {
            throw new Malformed();
        }
        long width = u32be(in, pos + 8);
        long height = u32be(in, pos + 12);
        if (width > PNG_MAX_DIMENSION || height > PNG_MAX_DIMENSION) {
            throw new Malformed();
        }
        return new Dimensions(width, height);
    }

    private static Dimensions jpegDimensions(byte[] in) {
        int pos = 2;
        while (pos < in.length) {
            int[] marker = nextJpegMarker(in, pos);
            pos = marker[1];
            int code = marker[0];
            if (code == EOI || code == SOS) {
                throw new Malformed();
            }
            if (isStandalone(code)) {
                continue;
            }
            int length = u16be(in, pos);
            if (length < 2) {
                throw new Malformed();
            }
            if (isStartOfFrame(code)) {
                need(in, pos, 7);
                return new Dimensions(u16be(in, pos + 5), u16be(in, pos + 3));
            }
            pos = segmentEnd(in, pos, length);
        }
        throw new Malformed();
    }

    private static Dimensions webpDimensions(byte[] in) {
        need(in, 12, 8);
        String type = fourCc(in, 12);
        int data = 20;
        return switch (type) {
            case "VP8 " -> {
                need(in, data, 10);
                if ((in[data + 3] & 0xFF) != 0x9D || (in[data + 4] & 0xFF) != 0x01 || (in[data + 5] & 0xFF) != 0x2A) {
                    throw new Malformed();
                }
                yield new Dimensions(u16le(in, data + 6) & 0x3FFF, u16le(in, data + 8) & 0x3FFF);
            }
            case "VP8L" -> {
                need(in, data, 5);
                if ((in[data] & 0xFF) != 0x2F) {
                    throw new Malformed();
                }
                long bits = u32le(in, data + 1);
                yield new Dimensions((bits & 0x3FFF) + 1, ((bits >> 14) & 0x3FFF) + 1);
            }
            case "VP8X" -> {
                need(in, data, 10);
                yield new Dimensions(u24le(in, data + 4) + 1L, u24le(in, data + 7) + 1L);
            }
            default -> throw new Malformed();
        };
    }

    private static byte[] stripPng(byte[] in) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
        out.write(in, 0, PNG_SIGNATURE.length);
        int pos = PNG_SIGNATURE.length;
        boolean first = true;
        boolean sawData = false;
        while (pos < in.length) {
            long length = u32be(in, pos);
            if (length > Integer.MAX_VALUE) {
                throw new Malformed();
            }
            int total = (int) length + 12;
            need(in, pos, total);
            String type = fourCc(in, pos + 4);
            if (first && !type.equals("IHDR")) {
                throw new Malformed();
            }
            first = false;
            if (PNG_KEPT.contains(type)) {
                out.write(in, pos, total);
            }
            sawData |= type.equals("IDAT");
            pos += total;
            if (type.equals("IEND")) {
                if (!sawData) {
                    throw new Malformed();
                }
                return out.toByteArray();
            }
        }
        throw new Malformed();
    }

    private static byte[] stripJpeg(byte[] in) {
        List<byte[]> kept = new ArrayList<>();
        List<Boolean> leading = new ArrayList<>();
        int orientation = 1;
        boolean sawExif = false;
        boolean sawFrame = false;
        boolean sawScan = false;
        boolean ended = false;
        int pos = 2;
        while (pos < in.length && !ended) {
            int[] marker = nextJpegMarker(in, pos);
            int code = marker[0];
            int start = marker[1] - 2;
            pos = marker[1];
            if (code == EOI) {
                ended = true;
                continue;
            }
            if (code == SOI) {
                throw new Malformed();
            }
            if (isStandalone(code)) {
                continue;
            }
            int length = u16be(in, pos);
            if (length < 2) {
                throw new Malformed();
            }
            int end = segmentEnd(in, pos, length);
            int payload = pos + 2;
            int payloadLength = length - 2;
            if (code == APP1 && !sawExif && startsWith(in, payload, EXIF)) {
                sawExif = true;
                orientation = exifOrientation(in, payload, payloadLength);
            }
            boolean keep = code == DQT || code == DHT || code == DRI || isStartOfFrame(code) || code == SOS
                    || code == APP0 && startsWith(in, payload, JFIF)
                    || code == APP2 && startsWith(in, payload, ICC_PROFILE)
                    || code == APP14 && startsWith(in, payload, ADOBE);
            if (isStartOfFrame(code)) {
                sawFrame = true;
            }
            if (code == SOS) {
                int scanEnd = scanEnd(in, end);
                kept.add(Arrays.copyOfRange(in, start, scanEnd));
                leading.add(false);
                sawScan = true;
                pos = scanEnd;
                continue;
            }
            if (keep) {
                kept.add(Arrays.copyOfRange(in, start, end));
                leading.add(code == APP0);
            }
            pos = end;
        }
        if (!ended || !sawFrame || !sawScan) {
            throw new Malformed();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
        out.write(0xFF);
        out.write(SOI);
        boolean orientationPending = orientation > 1;
        for (int i = 0; i < kept.size(); i++) {
            if (orientationPending && !leading.get(i)) {
                out.writeBytes(orientationSegment(orientation));
                orientationPending = false;
            }
            out.writeBytes(kept.get(i));
        }
        out.write(0xFF);
        out.write(EOI);
        return out.toByteArray();
    }

    private static byte[] stripWebp(byte[] in) {
        long riffSize = u32le(in, 4);
        if (riffSize < 4 || riffSize + 8 > in.length) {
            throw new Malformed();
        }
        int limit = (int) riffSize + 8;
        ByteArrayOutputStream body = new ByteArrayOutputStream(limit);
        boolean sawImage = false;
        int pos = 12;
        while (pos < limit) {
            need(in, pos, 8);
            String type = fourCc(in, pos);
            long size = u32le(in, pos + 4);
            long padded = size + (size & 1);
            if (pos + 8 + padded > limit) {
                throw new Malformed();
            }
            int data = pos + 8;
            int next = (int) (data + padded);
            if (type.equals("ANMF")) {
                writeChunk(body, "ANMF", frameBody(in, data, (int) size));
                sawImage = true;
            } else if (WEBP_KEPT.contains(type)) {
                byte[] payload = Arrays.copyOfRange(in, data, data + (int) size);
                if (type.equals("VP8X")) {
                    if (payload.length < 10) {
                        throw new Malformed();
                    }
                    payload[0] &= (byte) ~VP8X_METADATA_FLAGS;
                }
                sawImage |= type.equals("VP8 ") || type.equals("VP8L");
                writeChunk(body, type, payload);
            }
            pos = next;
        }
        if (!sawImage) {
            throw new Malformed();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(body.size() + 12);
        out.writeBytes(ascii("RIFF"));
        out.writeBytes(le32(body.size() + 4L));
        out.writeBytes(ascii("WEBP"));
        out.writeBytes(body.toByteArray());
        return out.toByteArray();
    }

    private static byte[] frameBody(byte[] in, int data, int size) {
        if (size < 16) {
            throw new Malformed();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(size);
        out.write(in, data, 16);
        int pos = data + 16;
        int end = data + size;
        while (pos < end) {
            if (pos + 8 > end) {
                throw new Malformed();
            }
            String type = fourCc(in, pos);
            long chunkSize = u32le(in, pos + 4);
            long padded = chunkSize + (chunkSize & 1);
            if (pos + 8 + padded > end) {
                throw new Malformed();
            }
            if (WEBP_FRAME_KEPT.contains(type)) {
                writeChunk(out, type, Arrays.copyOfRange(in, pos + 8, pos + 8 + (int) chunkSize));
            }
            pos += 8 + (int) padded;
        }
        return out.toByteArray();
    }

    private static void writeChunk(ByteArrayOutputStream out, String type, byte[] payload) {
        out.writeBytes(ascii(type));
        out.writeBytes(le32(payload.length));
        out.writeBytes(payload);
        if ((payload.length & 1) == 1) {
            out.write(0);
        }
    }

    /** Position after the entropy-coded data that follows a scan header, up to the next real marker. */
    private static int scanEnd(byte[] in, int from) {
        int pos = from;
        while (pos < in.length) {
            if ((in[pos] & 0xFF) != 0xFF) {
                pos++;
                continue;
            }
            need(in, pos, 2);
            int next = in[pos + 1] & 0xFF;
            if (next != 0x00 && (next < 0xD0 || next > 0xD7)) {
                return pos;
            }
            pos += 2;
        }
        throw new Malformed();
    }

    /** Returns {marker code, position after the two marker bytes}; fill bytes before the marker are skipped. */
    private static int[] nextJpegMarker(byte[] in, int from) {
        int pos = from;
        need(in, pos, 2);
        if ((in[pos] & 0xFF) != 0xFF) {
            throw new Malformed();
        }
        while (pos + 1 < in.length && (in[pos + 1] & 0xFF) == 0xFF) {
            pos++;
        }
        need(in, pos, 2);
        int code = in[pos + 1] & 0xFF;
        if (code == 0x00) {
            throw new Malformed();
        }
        return new int[] {code, pos + 2};
    }

    private static boolean isStandalone(int code) {
        return code == 0x01 || code >= 0xD0 && code <= 0xD8;
    }

    private static boolean isStartOfFrame(int code) {
        return code >= 0xC0 && code <= 0xCF && code != 0xC4 && code != 0xC8 && code != 0xCC;
    }

    private static int segmentEnd(byte[] in, int lengthPos, int length) {
        need(in, lengthPos, length);
        return lengthPos + length;
    }

    /** The EXIF orientation of an APP1 payload (1 when absent, out of range or unreadable). */
    private static int exifOrientation(byte[] in, int payload, int payloadLength) {
        try {
            int tiff = payload + EXIF.length;
            int end = payload + payloadLength;
            if (tiff + 8 > end) {
                return 1;
            }
            boolean big;
            if (in[tiff] == 'M' && in[tiff + 1] == 'M') {
                big = true;
            } else if (in[tiff] == 'I' && in[tiff + 1] == 'I') {
                big = false;
            } else {
                return 1;
            }
            long ifd = tiff + (big ? u32be(in, tiff + 4) : u32le(in, tiff + 4));
            if (ifd + 2 > end) {
                return 1;
            }
            int count = (int) (big ? u16be(in, (int) ifd) : u16le(in, (int) ifd));
            for (int i = 0; i < count; i++) {
                long entry = ifd + 2 + 12L * i;
                if (entry + 12 > end) {
                    return 1;
                }
                int e = (int) entry;
                int tag = big ? u16be(in, e) : u16le(in, e);
                int type = big ? u16be(in, e + 2) : u16le(in, e + 2);
                if (tag == ORIENTATION_TAG && type == TIFF_SHORT) {
                    int value = big ? u16be(in, e + 8) : u16le(in, e + 8);
                    return value >= 1 && value <= 8 ? value : 1;
                }
            }
            return 1;
        } catch (Malformed e) {
            return 1;
        }
    }

    /** A big-endian APP1 EXIF segment holding only the orientation tag. */
    private static byte[] orientationSegment(int orientation) {
        byte[] segment = {
            (byte) 0xFF, (byte) APP1, 0x00, 0x22,
            'E', 'x', 'i', 'f', 0, 0,
            'M', 'M', 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08,
            0x00, 0x01,
            0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, (byte) orientation, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00
        };
        return segment;
    }

    private static void need(byte[] in, int pos, long length) {
        if (pos < 0 || length < 0 || (long) pos + length > in.length) {
            throw new Malformed();
        }
    }

    private static boolean startsWith(byte[] in, int pos, byte[] prefix) {
        if (pos < 0 || (long) pos + prefix.length > in.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (in[pos + i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static int u16be(byte[] in, int pos) {
        need(in, pos, 2);
        return (in[pos] & 0xFF) << 8 | in[pos + 1] & 0xFF;
    }

    private static int u16le(byte[] in, int pos) {
        need(in, pos, 2);
        return in[pos] & 0xFF | (in[pos + 1] & 0xFF) << 8;
    }

    private static int u24le(byte[] in, int pos) {
        need(in, pos, 3);
        return in[pos] & 0xFF | (in[pos + 1] & 0xFF) << 8 | (in[pos + 2] & 0xFF) << 16;
    }

    private static long u32be(byte[] in, int pos) {
        need(in, pos, 4);
        return (long) (in[pos] & 0xFF) << 24 | (in[pos + 1] & 0xFF) << 16 | (in[pos + 2] & 0xFF) << 8
                | in[pos + 3] & 0xFF;
    }

    private static long u32le(byte[] in, int pos) {
        need(in, pos, 4);
        return in[pos] & 0xFF | (in[pos + 1] & 0xFF) << 8 | (in[pos + 2] & 0xFF) << 16
                | (long) (in[pos + 3] & 0xFF) << 24;
    }

    private static byte[] le32(long value) {
        return new byte[] {(byte) value, (byte) (value >> 8), (byte) (value >> 16), (byte) (value >> 24)};
    }

    private static String fourCc(byte[] in, int pos) {
        need(in, pos, 4);
        return new String(in, pos, 4, StandardCharsets.ISO_8859_1);
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}
