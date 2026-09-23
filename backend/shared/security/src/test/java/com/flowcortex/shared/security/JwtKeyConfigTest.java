package com.flowcortex.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;

/**
 * Plain-JUnit coverage of the fail-fast JWT_SECRET validation — no Spring
 * context needed, so this runs fast and covers the one piece of
 * :shared:security that is not itself a {@code *Config} exclusion detail.
 */
class JwtKeyConfigTest {

    @Test
    void blankSecretThrowsIllegalState() {
        assertThatThrownBy(() -> JwtKeyConfig.buildKey(" "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET is not set");
    }

    @Test
    void tooShortSecretThrowsIllegalState() {
        assertThatThrownBy(() -> JwtKeyConfig.buildKey("short-secret"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least " + JwtKeyConfig.MIN_SECRET_BYTES);
    }

    @Test
    void validSecretBuildsAnHmacShaKey() {
        SecretKey key = JwtKeyConfig.buildKey("a-valid-jwt-signing-secret-of-32-bytes-or-more");
        assertThat(key.getAlgorithm()).isEqualTo("HmacSHA256");
    }
}
