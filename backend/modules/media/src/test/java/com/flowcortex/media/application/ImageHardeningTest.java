package com.flowcortex.media.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.media.application.ImageHardening.Dimensions;
import com.flowcortex.media.application.ImageHardening.ImageFormat;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;

class ImageHardeningTest {

    private static final byte[] SCAN = {0x12, (byte) 0xFF, 0x00, 0x34, (byte) 0xFF, (byte) 0xD0, 0x56, 0x78};
    private static final byte[] ICC_PAYLOAD = cat(ascii("ICC_PROFILE\0"), new byte[] {1, 2, 3, 4, 5});

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    private static byte[] be16(int value) {
        return new byte[] {(byte) (value >> 8), (byte) value};
    }

    private static byte[] be32(long value) {
        return new byte[] {(byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8), (byte) value};
    }

    private static byte[] le16(int value) {
        return new byte[] {(byte) value, (byte) (value >> 8)};
    }

    private static byte[] le32(long value) {
        return new byte[] {(byte) value, (byte) (value >> 8), (byte) (value >> 16), (byte) (value >> 24)};
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            if (Arrays.equals(Arrays.copyOfRange(haystack, i, i + needle.length), needle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(byte[] haystack, String needle) {
        return contains(haystack, ascii(needle));
    }

    private static byte[] jpegSegment(int marker, byte[] payload) {
        return cat(new byte[] {(byte) 0xFF, (byte) marker}, be16(payload.length + 2), payload);
    }

    private static byte[] sof(int marker, int width, int height) {
        return jpegSegment(marker, cat(new byte[] {8}, be16(height), be16(width), new byte[] {1, 1, 0x11, 0}));
    }

    private static byte[] sos(byte[] scan) {
        return cat(jpegSegment(0xDA, new byte[] {1, 1, 0, 0, 63, 0}), scan);
    }

    private static byte[] jpeg(byte[]... segments) {
        return cat(new byte[] {(byte) 0xFF, (byte) 0xD8}, cat(segments), new byte[] {(byte) 0xFF, (byte) 0xD9});
    }

    private static byte[] plainJpeg() {
        return jpeg(sof(0xC0, 40, 30), jpegSegment(0xDB, new byte[] {0, 1, 2}), sos(SCAN));
    }

    private static byte[] exif(boolean bigEndian, int orientation) {
        byte[] model = ascii("Canon EOS\0");
        byte[] tiffHeader = bigEndian
                ? cat(ascii("MM"), be16(42), be32(8))
                : cat(ascii("II"), le16(42), le32(8));
        byte[] ifd = bigEndian
                ? cat(be16(2), be16(0x0110), be16(2), be32(model.length), be32(38),
                        be16(0x0112), be16(3), be32(1), be16(orientation), be16(0), be32(0))
                : cat(le16(2), le16(0x0110), le16(2), le32(model.length), le32(38),
                        le16(0x0112), le16(3), le32(1), le16(orientation), le16(0), le32(0));
        return cat(ascii("Exif\0\0"), tiffHeader, ifd, model, ascii("GPSLatitude"));
    }

    private static byte[] minimalOrientation(int orientation) {
        return new byte[] {
            (byte) 0xFF, (byte) 0xE1, 0x00, 0x22, 'E', 'x', 'i', 'f', 0, 0,
            'M', 'M', 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08, 0x00, 0x01,
            0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, (byte) orientation, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00
        };
    }

    private static byte[] pngChunk(String type, byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(ascii(type));
        crc.update(data);
        return cat(be32(data.length), ascii(type), data, be32(crc.getValue()));
    }

    private static byte[] ihdr(long width, long height, int bitDepth) {
        return pngChunk("IHDR", cat(be32(width), be32(height), new byte[] {(byte) bitDepth, 2, 0, 0, 0}));
    }

    private static byte[] png(long width, long height, int bitDepth, byte[]... chunks) {
        return cat(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, ihdr(width, height, bitDepth),
                cat(chunks), pngChunk("IDAT", new byte[] {9, 8, 7, 6, 5}), pngChunk("IEND", new byte[0]));
    }

    private static byte[] webpChunk(String type, byte[] data) {
        byte[] padding = data.length % 2 == 1 ? new byte[] {0} : new byte[0];
        return cat(ascii(type), le32(data.length), data, padding);
    }

    private static byte[] riff(byte[]... chunks) {
        byte[] body = cat(chunks);
        return cat(ascii("RIFF"), le32(body.length + 4L), ascii("WEBP"), body);
    }

    private static byte[] vp8(int width, int height) {
        return webpChunk("VP8 ", cat(new byte[] {0x10, 0, 0, (byte) 0x9D, 0x01, 0x2A}, le16(width), le16(height),
                new byte[] {1, 2, 3}));
    }

    private static byte[] vp8l(int width, int height) {
        long bits = (width - 1) | ((long) (height - 1) << 14);
        return webpChunk("VP8L", cat(new byte[] {0x2F}, le32(bits), new byte[] {1, 2, 3}));
    }

    private static byte[] vp8x(int flags, int width, int height) {
        return webpChunk("VP8X", cat(new byte[] {(byte) flags, 0, 0, 0}, le24(width - 1), le24(height - 1)));
    }

    private static byte[] le24(int value) {
        return new byte[] {(byte) value, (byte) (value >> 8), (byte) (value >> 16)};
    }

    private static byte[] stripped(ImageFormat format, byte[] bytes) {
        return ImageHardening.strip(format, bytes).orElseThrow();
    }

    @Test
    void pngDimensionsComeFromIhdr() {
        assertThat(ImageHardening.dimensions(ImageFormat.PNG, png(640, 480, 8)))
                .contains(new Dimensions(640, 480));
    }

    @Test
    void jpegDimensionsComeFromTheFirstSofMarkerAfterApp1() {
        byte[] bytes = jpeg(jpegSegment(0xE1, exif(true, 1)), jpegSegment(0xDB, new byte[] {0}),
                sof(0xC2, 300, 200), sos(SCAN));

        assertThat(ImageHardening.dimensions(ImageFormat.JPEG, bytes)).contains(new Dimensions(300, 200));
    }

    @Test
    void webpDimensionsComeFromVp8Vp8lAndVp8x() {
        assertThat(ImageHardening.dimensions(ImageFormat.WEBP, riff(vp8(320, 240))))
                .contains(new Dimensions(320, 240));
        assertThat(ImageHardening.dimensions(ImageFormat.WEBP, riff(vp8l(1000, 16384))))
                .contains(new Dimensions(1000, 16384));
        assertThat(ImageHardening.dimensions(ImageFormat.WEBP, riff(vp8x(0x10, 4000, 3000), vp8(10, 10))))
                .contains(new Dimensions(4000, 3000));
    }

    @Test
    void aTruncatedOrUnknownHeaderHasNoDimensions() {
        byte[] png = png(640, 480, 8);
        byte[] jpeg = plainJpeg();

        assertThat(ImageHardening.dimensions(ImageFormat.PNG, Arrays.copyOf(png, 20))).isEmpty();
        assertThat(ImageHardening.dimensions(ImageFormat.JPEG, Arrays.copyOf(jpeg, 8))).isEmpty();
        assertThat(ImageHardening.dimensions(ImageFormat.WEBP, riff(webpChunk("ABCD", new byte[4])))).isEmpty();
        assertThat(ImageHardening.dimensions(ImageFormat.WEBP, Arrays.copyOf(riff(vp8(10, 10)), 14))).isEmpty();
        assertThat(ImageHardening.dimensions(ImageFormat.PNG, png(0, 480, 8))).isEmpty();
    }

    @Test
    void jpegDhtBeforeSofIsNotReadAsDimensions() {
        byte[] dht = jpegSegment(0xC4, new byte[] {0, 0x01, 0x2C, 0x00, (byte) 0xC8, 0, 0, 0});
        byte[] bytes = jpeg(dht, sof(0xC0, 7, 9), sos(SCAN));

        assertThat(ImageHardening.dimensions(ImageFormat.JPEG, bytes)).contains(new Dimensions(7, 9));
    }

    @Test
    void aTextFileIsNotAnImageNotTooLarge() {
        byte[] text = ascii("this is just a text file pretending to be a photo");

        assertThat(ImageHardening.detect(text)).isEmpty();
        assertThat(ImageHardening.detect(new byte[0])).isEmpty();
    }

    @Test
    void aPngNamedJpgIsRefusedAsNotAnImage() {
        ImageFormat detected = ImageHardening.detect(png(10, 10, 8)).orElseThrow();

        assertThat(detected).isEqualTo(ImageFormat.PNG);
        assertThat(detected.mimeType()).isNotEqualTo("image/jpeg").isEqualTo("image/png");
        assertThat(ImageHardening.detect(plainJpeg()).orElseThrow().mimeType()).isEqualTo("image/jpeg");
        assertThat(ImageHardening.detect(riff(vp8(10, 10))).orElseThrow().mimeType()).isEqualTo("image/webp");
    }

    @Test
    void exactlyTwentyFiveMegapixelsIsWithinTheCapAndOneMorePixelIsNot() {
        assertThat(ImageHardening.withinPixelCap(new Dimensions(5000, 5000))).isTrue();
        assertThat(ImageHardening.withinPixelCap(new Dimensions(5001, 5000))).isFalse();
        assertThat(ImageHardening.withinPixelCap(ImageHardening.dimensions(ImageFormat.PNG, png(20000, 20000, 8))
                .orElseThrow())).isFalse();
    }

    @Test
    void jpegLosesExifXmpIptcAndCommentButKeepsIccAndScanDataByteForByte() {
        byte[] icc = jpegSegment(0xE2, ICC_PAYLOAD);
        byte[] bytes = jpeg(jpegSegment(0xE0, cat(ascii("JFIF\0"), new byte[] {1, 1, 0, 0, 1, 0, 1, 0, 0})),
                jpegSegment(0xE1, exif(false, 1)),
                jpegSegment(0xE1, cat(ascii("http://ns.adobe.com/xap/1.0/\0"), ascii("<x:xmpmeta/>"))),
                jpegSegment(0xED, cat(ascii("Photoshop 3.0\0"), ascii("IPTC-CAPTION"))),
                jpegSegment(0xFE, ascii("secret comment")), icc,
                jpegSegment(0xDB, new byte[] {0, 1, 2}), sof(0xC0, 40, 30), sos(SCAN));

        byte[] out = stripped(ImageFormat.JPEG, bytes);

        assertThat(contains(out, "GPSLatitude")).isFalse();
        assertThat(contains(out, "Canon")).isFalse();
        assertThat(contains(out, "xmpmeta")).isFalse();
        assertThat(contains(out, "IPTC-CAPTION")).isFalse();
        assertThat(contains(out, "secret comment")).isFalse();
        assertThat(contains(out, "Exif")).isFalse();
        assertThat(contains(out, icc)).isTrue();
        assertThat(contains(out, cat(sos(SCAN)))).isTrue();
        assertThat(ImageHardening.dimensions(ImageFormat.JPEG, out)).contains(new Dimensions(40, 30));
    }

    @Test
    void jpegTrailerAfterEoiAndMpfApp2AreDropped() {
        byte[] mpf = jpegSegment(0xE2, cat(ascii("MPF\0"), ascii("SECONDARY-IMAGE")));
        byte[] bytes = cat(jpeg(mpf, sof(0xC0, 40, 30), sos(SCAN)), ascii("MOTION-PHOTO-VIDEO"));

        byte[] out = stripped(ImageFormat.JPEG, bytes);

        assertThat(contains(out, "SECONDARY-IMAGE")).isFalse();
        assertThat(contains(out, "MOTION-PHOTO-VIDEO")).isFalse();
        assertThat(out).isEqualTo(jpeg(sof(0xC0, 40, 30), sos(SCAN)));
    }

    @Test
    void pngPrivateChunkAndDataAfterIendAreDropped() {
        byte[] bytes = cat(png(10, 10, 8, pngChunk("prVt", ascii("HIDDEN-CHUNK"))), ascii("HIDDEN-TAIL"));

        byte[] out = stripped(ImageFormat.PNG, bytes);

        assertThat(contains(out, "HIDDEN-CHUNK")).isFalse();
        assertThat(contains(out, "HIDDEN-TAIL")).isFalse();
        assertThat(out).isEqualTo(png(10, 10, 8));
    }

    @Test
    void webpUnknownChunkIsDropped() {
        byte[] out = stripped(ImageFormat.WEBP, riff(vp8(10, 10), webpChunk("JUNK", ascii("HIDDEN-WEBP"))));

        assertThat(contains(out, "HIDDEN-WEBP")).isFalse();
        assertThat(out).isEqualTo(riff(vp8(10, 10)));
    }

    @Test
    void progressiveAndCmykJpegSixteenBitPngAndAnimatedWebpAreKeptDisplayable() {
        byte[] progressive = jpeg(jpegSegment(0xEE, cat(ascii("Adobe"), new byte[] {0, 100, 0, 0, 0, 0, 2})),
                jpegSegment(0xDB, new byte[] {0}), sof(0xC2, 64, 48), jpegSegment(0xC4, new byte[] {0, 1, 2}),
                sos(SCAN), jpegSegment(0xC4, new byte[] {0, 3, 4}), jpegSegment(0xDD, new byte[] {0, 8}),
                sos(new byte[] {0x21, 0x22}));
        byte[] sixteenBit = png(32, 32, 16, pngChunk("tRNS", new byte[] {0, 1}), pngChunk("pHYs", new byte[9]));
        byte[] frame = webpChunk("ANMF", cat(new byte[16], vp8(10, 10)));
        byte[] animated = riff(vp8x(0x12, 10, 10), webpChunk("ANIM", new byte[6]), frame, frame);

        assertThat(stripped(ImageFormat.JPEG, progressive)).isEqualTo(progressive);
        assertThat(stripped(ImageFormat.PNG, sixteenBit)).isEqualTo(sixteenBit);
        assertThat(stripped(ImageFormat.WEBP, animated)).isEqualTo(animated);
    }

    @Test
    void jpegOrientationSixSurvivesInBigAndLittleEndianSources() {
        for (boolean bigEndian : new boolean[] {true, false}) {
            byte[] bytes = jpeg(jpegSegment(0xE1, exif(bigEndian, 6)), sof(0xC0, 40, 30), sos(SCAN));

            byte[] out = stripped(ImageFormat.JPEG, bytes);

            assertThat(contains(out, minimalOrientation(6))).isTrue();
            assertThat(contains(out, "GPSLatitude")).isFalse();
            assertThat(stripped(ImageFormat.JPEG, out)).isEqualTo(out);
        }
    }

    @Test
    void jpegWithOrientationOneOrNoneGetsNoExif() {
        byte[] one = jpeg(jpegSegment(0xE1, exif(true, 1)), sof(0xC0, 40, 30), sos(SCAN));

        assertThat(contains(stripped(ImageFormat.JPEG, one), "Exif")).isFalse();
        assertThat(contains(stripped(ImageFormat.JPEG, plainJpeg()), "Exif")).isFalse();
    }

    @Test
    void pngLosesExifTextItxtZtxtTimeChunksAndKeepsIdatAndIccp() {
        byte[] iccp = pngChunk("iCCP", ascii("profile\0\0data"));
        byte[] bytes = png(10, 10, 8, pngChunk("tEXt", ascii("Comment\0hello")), pngChunk("eXIf", ascii("EXIF-DATA")),
                pngChunk("iTXt", ascii("XML:com.adobe.xmp\0XMP-DATA")), pngChunk("zTXt", ascii("k\0\0Z-DATA")),
                pngChunk("tIME", new byte[7]), iccp);

        byte[] out = stripped(ImageFormat.PNG, bytes);

        assertThat(contains(out, "hello")).isFalse();
        assertThat(contains(out, "EXIF-DATA")).isFalse();
        assertThat(contains(out, "XMP-DATA")).isFalse();
        assertThat(contains(out, "Z-DATA")).isFalse();
        assertThat(contains(out, "tIME")).isFalse();
        assertThat(contains(out, iccp)).isTrue();
        assertThat(contains(out, pngChunk("IDAT", new byte[] {9, 8, 7, 6, 5}))).isTrue();
    }

    @Test
    void webpLosesExifAndXmpChunksClearsTheirVp8xFlagsAndFixesTheRiffSize() {
        byte[] bytes = cat(riff(vp8x(0x20 | 0x10 | 0x08 | 0x04, 10, 10), webpChunk("ICCP", new byte[5]),
                webpChunk("EXIF", ascii("EXIF-WEBP")), webpChunk("XMP ", ascii("XMP-WEBP!")), vp8(10, 10)),
                ascii("TRAILING"));

        byte[] out = stripped(ImageFormat.WEBP, bytes);

        assertThat(out).isEqualTo(riff(vp8x(0x20 | 0x10, 10, 10), webpChunk("ICCP", new byte[5]), vp8(10, 10)));
    }

    @Test
    void aTruncatedImageCannotBeStripped() {
        assertThat(ImageHardening.strip(ImageFormat.JPEG, Arrays.copyOf(plainJpeg(), 20))).isEmpty();
        assertThat(ImageHardening.strip(ImageFormat.PNG, Arrays.copyOf(png(10, 10, 8), 40))).isEmpty();
        assertThat(ImageHardening.strip(ImageFormat.WEBP, Arrays.copyOf(riff(vp8(10, 10)), 20))).isEmpty();
    }
}
