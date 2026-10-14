package com.flowcortex.user.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class SafeReturnToTest {

    @ParameterizedTest
    @ValueSource(strings = {"/", "/runs", "/runs/42?tab=2#top", "/a/b/c", "/%2F%2Fescaped"})
    void aRelativePathIsKept(String path) {
        assertThat(SafeReturnTo.of(path)).isEqualTo(path);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"//evil.example", "https://evil.example", "http://evil.example/x", "/\\evil.example",
            "\\\\evil.example", "runs", "javascript:alert(1)", "/ok\r\nSet-Cookie: x=y", "/a\tb", " //evil.example"})
    void anythingElseFallsBackToTheRoot(String value) {
        assertThat(SafeReturnTo.of(value)).isEqualTo("/");
    }

    @Test
    void anOverlongPathFallsBackToTheRoot() {
        assertThat(SafeReturnTo.of("/" + "a".repeat(600))).isEqualTo("/");
    }
}
