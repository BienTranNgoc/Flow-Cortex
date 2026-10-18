package com.flowcortex.media.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ContentSnifferTest {

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    private static byte[] riff(String form) {
        byte[] out = new byte[16];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, out, 0, 4);
        out[4] = 0x24;
        System.arraycopy(form.getBytes(StandardCharsets.US_ASCII), 0, out, 8, 4);
        return out;
    }

    private static byte[] ftyp(String brand) {
        byte[] out = new byte[16];
        out[3] = 0x18;
        System.arraycopy("ftyp".getBytes(StandardCharsets.US_ASCII), 0, out, 4, 4);
        System.arraycopy(brand.getBytes(StandardCharsets.US_ASCII), 0, out, 8, 4);
        return out;
    }

    static Stream<Arguments> signatures() {
        return Stream.of(
                Arguments.of("mp4 ftyp", "video", ftyp("isom")),
                Arguments.of("mov ftyp", "video", ftyp("qt  ")),
                Arguments.of("webm/mkv EBML", "video", bytes(0x1A, 0x45, 0xDF, 0xA3, 0x9F, 0x42, 0x86, 0x81)),
                Arguments.of("m4a ftyp", "audio", ftyp("M4A ")),
                Arguments.of("mp3 ID3", "audio", bytes('I', 'D', '3', 0x04, 0x00, 0x00, 0x00, 0x00)),
                Arguments.of("mp3 frame sync FFFB", "audio", bytes(0xFF, 0xFB, 0x90, 0x64)),
                Arguments.of("mp3 frame sync FFE3", "audio", bytes(0xFF, 0xE3, 0x18, 0xC4)),
                Arguments.of("wav RIFF WAVE", "audio", riff("WAVE")),
                Arguments.of("png", "image", bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)),
                Arguments.of("jpeg", "image", bytes(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10)),
                Arguments.of("webp RIFF WEBP", "image", riff("WEBP")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("signatures")
    void eachRecognisedSignatureMatchesItsKind(String name, String kind, byte[] prefix) {
        assertThat(ContentSniffer.matches(kind, prefix)).isTrue();
    }

    @Test
    void textBytesAreNotAVideo() {
        assertThat(ContentSniffer.matches("video", "hello".getBytes(StandardCharsets.US_ASCII))).isFalse();
    }

    @Test
    void aPngHeaderIsNotAnAudioFile() {
        assertThat(ContentSniffer.matches("audio", bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))).isFalse();
    }

    @Test
    void aWavIsNotAnImageAndAWebpIsNotAnAudioFile() {
        assertThat(ContentSniffer.matches("image", riff("WAVE"))).isFalse();
        assertThat(ContentSniffer.matches("audio", riff("WEBP"))).isFalse();
    }

    @Test
    void anEmptyPrefixOrAnUnknownKindNeverMatches() {
        assertThat(ContentSniffer.matches("video", new byte[0])).isFalse();
        assertThat(ContentSniffer.matches("text", ftyp("isom"))).isFalse();
    }
}
