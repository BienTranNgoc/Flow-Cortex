package com.flowcortex.user.apicall;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class IdempotencyKeyTest {

    private static final long ONE_MIB = 1024 * 1024;

    private static byte[] body(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void theKeyIsTrimmed() {
        assertThat(IdempotencyKey.normalize("  k  ")).contains("k");
    }

    @Test
    void aBlankKeyIsInvalid() {
        assertThat(IdempotencyKey.normalize("   ")).isEmpty();
        assertThat(IdempotencyKey.normalize("")).isEmpty();
    }

    @Test
    void eightyCharactersAreValidAndEightyOneAreNot() {
        assertThat(IdempotencyKey.normalize("k".repeat(80))).isPresent();
        assertThat(IdempotencyKey.normalize("k".repeat(81))).isEmpty();
    }

    @Test
    void onlyPostAndPatchAreKeyed() {
        assertThat(IdempotencyKey.appliesTo("POST")).isTrue();
        assertThat(IdempotencyKey.appliesTo("PATCH")).isTrue();
        assertThat(IdempotencyKey.appliesTo("PUT")).isFalse();
        assertThat(IdempotencyKey.appliesTo("DELETE")).isFalse();
    }

    @Test
    void aMultipartBodyIsUnsupported() {
        assertThat(IdempotencyKey.unsupported("multipart/form-data; boundary=x", 10, ONE_MIB)).isTrue();
        assertThat(IdempotencyKey.unsupported("MULTIPART/MIXED", 10, ONE_MIB)).isTrue();
    }

    @Test
    void aBodyOverTheLimitIsUnsupportedAndOneAtTheLimitIsNot() {
        assertThat(IdempotencyKey.unsupported("application/json", ONE_MIB + 1, ONE_MIB)).isTrue();
        assertThat(IdempotencyKey.unsupported("application/json", ONE_MIB, ONE_MIB)).isFalse();
        assertThat(IdempotencyKey.unsupported(null, 0, ONE_MIB)).isFalse();
    }

    @Test
    void theFingerprintIsSha256Hex() {
        assertThat(IdempotencyKey.fingerprint("POST", "/api/v1/workflows", null, body("{}"))).matches("[0-9a-f]{64}");
    }

    @Test
    void theSameRequestHasTheSameFingerprint() {
        assertThat(IdempotencyKey.fingerprint("POST", "/api/v1/workflows", "a=1", body("{}")))
                .isEqualTo(IdempotencyKey.fingerprint("POST", "/api/v1/workflows", "a=1", body("{}")));
    }

    @Test
    void aNullAndAnEmptyQueryAreTheSameRequest() {
        assertThat(IdempotencyKey.fingerprint("POST", "/p", null, body("{}")))
                .isEqualTo(IdempotencyKey.fingerprint("POST", "/p", "", body("{}")));
    }

    @Test
    void methodPathQueryAndOneBodyByteEachChangeTheFingerprint() {
        String base = IdempotencyKey.fingerprint("POST", "/api/v1/uploads", "a=1", body("{\"n\":1}"));

        assertThat(IdempotencyKey.fingerprint("PATCH", "/api/v1/uploads", "a=1", body("{\"n\":1}"))).isNotEqualTo(base);
        assertThat(IdempotencyKey.fingerprint("POST", "/api/v1/runs", "a=1", body("{\"n\":1}"))).isNotEqualTo(base);
        assertThat(IdempotencyKey.fingerprint("POST", "/api/v1/uploads", "a=2", body("{\"n\":1}"))).isNotEqualTo(base);
        assertThat(IdempotencyKey.fingerprint("POST", "/api/v1/uploads", null, body("{\"n\":1}"))).isNotEqualTo(base);
        assertThat(IdempotencyKey.fingerprint("POST", "/api/v1/uploads", "a=1", body("{\"n\":2}"))).isNotEqualTo(base);
    }
}
