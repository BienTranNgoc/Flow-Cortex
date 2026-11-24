package com.flowcortex.user.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class RedirectUrisTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "https://claude.ai/api/mcp/auth_callback",
            "http://localhost:8765/cb",
            "http://127.0.0.1/cb",
            "http://[::1]:9000/cb",
            "https://a.example/cb?tenant=7"})
    void validateForRegistrationAcceptsHttpsAndLoopbackHttp(String uri) {
        assertThat(RedirectUris.validateForRegistration(List.of(uri))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://evil.example/cb",
            "https://a.example/cb#frag",
            "https://user:pw@a.example/cb",
            "myapp://cb",
            "/relative",
            "relative/path",
            "https:opaque",
            "https://a.example/has space"})
    void validateForRegistrationRefusesEveryOtherShape(String uri) {
        assertThat(RedirectUris.validateForRegistration(List.of(uri))).isPresent();
    }

    @Test
    void validateForRegistrationRefusesALongUriAnEmptyListTooManyUrisAndNullEntries() {
        String tooLong = "https://a.example/" + "x".repeat(2001 - "https://a.example/".length());
        String longest = "https://a.example/" + "x".repeat(2000 - "https://a.example/".length());

        assertThat(tooLong).hasSize(2001);
        assertThat(RedirectUris.validateForRegistration(List.of(tooLong))).isPresent();
        assertThat(RedirectUris.validateForRegistration(List.of(longest))).isEmpty();
        assertThat(RedirectUris.validateForRegistration(List.of())).isPresent();
        assertThat(RedirectUris.validateForRegistration(null)).isPresent();
        assertThat(RedirectUris.validateForRegistration(
                List.of("https://a.example/1", "https://a.example/2", "https://a.example/3", "https://a.example/4",
                        "https://a.example/5", "https://a.example/6"))).isPresent();
        assertThat(RedirectUris.validateForRegistration(
                List.of("https://a.example/1", "https://a.example/2", "https://a.example/3", "https://a.example/4",
                        "https://a.example/5"))).isEmpty();
        assertThat(RedirectUris.validateForRegistration(java.util.Arrays.asList("https://a.example/1", null)))
                .isPresent();
    }

    static Stream<Arguments> exactHttpsPairs() {
        return Stream.of(
                Arguments.of("https://claude.ai/api/mcp/auth_callback", "https://claude.ai/api/mcp/auth_callback/"),
                Arguments.of("https://claude.ai/api/mcp/auth_callback", "https://claude.ai:8443/api/mcp/auth_callback"),
                Arguments.of("https://a.example/cb?tenant=7", "https://a.example/cb?tenant=8"),
                Arguments.of("https://127.0.0.1/cb", "https://127.0.0.1:8443/cb"));
    }

    @ParameterizedTest
    @MethodSource("exactHttpsPairs")
    void matchesIsExactForHttps(String registered, String requested) {
        assertThat(RedirectUris.matches(registered, registered)).isTrue();
        assertThat(RedirectUris.matches(registered, requested)).isFalse();
    }

    @Test
    void matchesIgnoresOnlyThePortOfARegisteredLoopbackHttpUri() {
        assertThat(RedirectUris.matches("http://127.0.0.1/callback", "http://127.0.0.1:53682/callback")).isTrue();
        assertThat(RedirectUris.matches("http://localhost:8765/cb", "http://localhost:9999/cb")).isTrue();
        assertThat(RedirectUris.matches("http://[::1]:9000/cb", "http://[::1]:1234/cb")).isTrue();
        assertThat(RedirectUris.matches("http://127.0.0.1/callback", "http://127.0.0.1:53682/other")).isFalse();
        assertThat(RedirectUris.matches("http://127.0.0.1/callback", "https://127.0.0.1:53682/callback")).isFalse();
        assertThat(RedirectUris.matches("http://127.0.0.1/callback", "http://localhost:53682/callback")).isFalse();
        assertThat(RedirectUris.matches("http://127.0.0.1/callback?a=1", "http://127.0.0.1:53682/callback?a=2"))
                .isFalse();
        assertThat(RedirectUris.matches("http://127.0.0.1/callback", "http://127.0.0.1:53682/callback#x")).isFalse();
        assertThat(RedirectUris.matches("http://127.0.0.1/callback", "http://u@127.0.0.1:53682/callback")).isFalse();
    }

    @Test
    void matchesRefusesNullAndUnparseableInput() {
        assertThat(RedirectUris.matches("https://a.example/cb", null)).isFalse();
        assertThat(RedirectUris.matches(null, "https://a.example/cb")).isFalse();
        assertThat(RedirectUris.matches("http://127.0.0.1/cb", "http://127.0.0.1:99/a b")).isFalse();
    }
}
