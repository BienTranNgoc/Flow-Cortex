package com.flowcortex.user.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OpaqueTokensTest {

    @Test
    void generateRawTokenReturnsBase64UrlEncodedBytes() {
        String token = OpaqueTokens.generateRawToken();
        assertThat(token).isNotEmpty();
        assertThat(token).matches("^[A-Za-z0-9_-]+$");
    }

    @Test
    void generateRawTokenReturnsAtLeast32BytesEncoded() {
        String token = OpaqueTokens.generateRawToken();
        // 32 bytes base64url-no-pad => ceil(32 * 4 / 3) = 43 chars.
        assertThat(token.length()).isGreaterThanOrEqualTo(43);
    }

    @Test
    void generateRawTokenReturnsUniqueValues() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            assertThat(seen.add(OpaqueTokens.generateRawToken())).isTrue();
        }
    }

    @Test
    void hashIsDeterministicForSameInput() {
        String raw = "fixed-test-token";
        assertThat(OpaqueTokens.hash(raw)).isEqualTo(OpaqueTokens.hash(raw));
    }

    @Test
    void hashProduces64CharacterHex() {
        String hash = OpaqueTokens.hash("any-input");
        assertThat(hash).hasSize(64);
        assertThat(hash).matches("^[0-9a-f]{64}$");
    }

    @Test
    void hashDiffersAcrossInputs() {
        String a = OpaqueTokens.hash("token-a");
        String b = OpaqueTokens.hash("token-b");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void hashOfKnownValueMatchesSha256() {
        // SHA-256 of "abc" is well known: ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad.
        assertThat(OpaqueTokens.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
