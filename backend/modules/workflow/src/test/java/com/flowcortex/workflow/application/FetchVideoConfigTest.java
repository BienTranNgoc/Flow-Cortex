package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class FetchVideoConfigTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "https://cdn.example.com/clip.mp4",
            "http://x.example/a",
            "https://www.youtube.com/watch?v=abc&t=3",
            "HTTPS://CDN.EXAMPLE.COM/A.MP4",
            "https://cdn.example.com:8443/a.mp4",
            " https://cdn.example.com/clip.mp4 "})
    void aLinkWithHttpOrHttpsAndAHostIsAccepted(String link) {
        assertThat(FetchVideoConfig.linkFault(link)).isNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "   ",
            "hello",
            "ftp://x.example/a.mp4",
            "file:///etc/passwd",
            "https:///a",
            "https://:8080/a",
            "https://user:pw@cdn.example.com/a.mp4",
            "https://user@cdn.example.com/a.mp4",
            "http://h/a b",
            "https://x.example/a\r\nHost: evil.example",
            "https://x.example/a\tb",
            "https://x.example/a\u0000b"})
    void aValueThatIsNotAPlainHttpLinkIsRefused(String link) {
        assertThat(FetchVideoConfig.linkFault(link)).isNotBlank();
    }

    @Test
    void aRefusedLinkIsNeverEchoedBackIncludingItsCredentials() {
        assertThat(FetchVideoConfig.linkFault("https://user:pw@cdn.example.com/a.mp4")).doesNotContain("pw");
    }

    @Test
    void theLinkLengthLimitIs2048CharactersAfterTrimming() {
        String prefix = "https://x.example/";
        assertThat(FetchVideoConfig.linkFault(prefix + "a".repeat(2048 - prefix.length()))).isNull();
        assertThat(FetchVideoConfig.linkFault(prefix + "a".repeat(2049 - prefix.length()))).isNotBlank();
        assertThat(FetchVideoConfig.linkFault(" " + prefix + "a".repeat(2048 - prefix.length()) + " ")).isNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "{}",
            "{\"url\":\"https://www.tiktok.com/@a/video/1\",\"quality\":\"720p\",\"extractAudio\":false}",
            "{\"quality\":\"best\"}",
            "{\"quality\":\"1080p\"}",
            "{\"quality\":\"audio_only\"}",
            "{\"extractAudio\":true}"})
    void aConfigWithOnlyUrlQualityAndExtractAudioIsAccepted(String config) {
        assertThat(FetchVideoConfig.fault(config)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"quality\":\"4k\"}",
            "{\"quality\":720}",
            "{\"quality\":null}",
            "{\"link\":\"https://x.example/a.mp4\"}",
            "{\"extractAudio\":\"no\"}",
            "{\"extractAudio\":null}",
            "{\"url\":\"hello\"}",
            "{\"url\":42}",
            "{\"url\":null}",
            "{\"url\":\"https://user:pw@cdn.example.com/a.mp4\"}",
            "{\"outputInputs\":[{\"stepOrder\":1,\"outputName\":\"video\",\"inputKey\":\"media\"}]}",
            "[]",
            "\"text\"",
            "{not json"})
    void aConfigWithAnotherKeyOrAValueOutsideTheRulesIsRefused(String config) {
        assertThat(FetchVideoConfig.fault(config)).isNotBlank();
    }

    @Test
    void aConfigFaultNeverEchoesTheCredentialsOfItsUrl() {
        assertThat(FetchVideoConfig.fault("{\"url\":\"https://user:pw@cdn.example.com/a.mp4\"}"))
                .doesNotContain("pw");
    }

    @Test
    void theConfigUrlIsReadTrimmedAndAbsentWhenNotSet() {
        assertThat(FetchVideoConfig.configUrl("{\"url\":\" https://www.youtube.com/watch?v=abc \"}"))
                .contains("https://www.youtube.com/watch?v=abc");
        assertThat(FetchVideoConfig.configUrl(null)).isEqualTo(Optional.empty());
        assertThat(FetchVideoConfig.configUrl("")).isEmpty();
        assertThat(FetchVideoConfig.configUrl("{}")).isEmpty();
        assertThat(FetchVideoConfig.configUrl("{\"url\":\"  \"}")).isEmpty();
        assertThat(FetchVideoConfig.configUrl("{not json")).isEmpty();
        assertThat(FetchVideoConfig.configUrl("[]")).isEmpty();
    }
}
