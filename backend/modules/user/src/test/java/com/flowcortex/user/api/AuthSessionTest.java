package com.flowcortex.user.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class AuthSessionTest {

    @Test
    void toStringMasksBothTokens() {
        AuthSession session = new AuthSession("access-secret", 900L, "refresh-secret",
                Instant.parse("2026-11-05T00:00:00Z"), true);

        assertThat(session.toString()).doesNotContain("access-secret").doesNotContain("refresh-secret")
                .contains("2026-11-05T00:00:00Z");
    }
}
