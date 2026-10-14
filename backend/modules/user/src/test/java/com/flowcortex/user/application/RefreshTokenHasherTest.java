package com.flowcortex.user.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RefreshTokenHasherTest {

    @Test
    void generateRawTokenReturnsBase64UrlEncodedBytes() {
        String token = RefreshTokenHasher.generateRawToken();
        assertThat(token).isNotEmpty();
        assertThat(token).matches("^[A-Za-z0-9_-]+$");
    }

    @Test
    void generateRawTokenReturnsAtLeast32BytesEncoded() {
        String token = RefreshTokenHasher.generateRawToken();
        // 32 bytes base64url-no-pad => ceil(32 * 4 / 3) = 43 chars.
        assertThat(token.length()).isGreaterThanOrEqualTo(43);
    }

    @Test
    void generateRawTokenReturnsUniqueValues() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            assertThat(seen.add(RefreshTokenHasher.generateRawToken())).isTrue();
        }
    }

    @Test
    void hashIsDeterministicForSameInput() {
        String raw = "fixed-test-token";
        assertThat(RefreshTokenHasher.hash(raw)).isEqualTo(RefreshTokenHasher.hash(raw));
    }

    @Test
    void hashProduces64CharacterHex() {
        String hash = RefreshTokenHasher.hash("any-input");
        assertThat(hash).hasSize(64);
        assertThat(hash).matches("^[0-9a-f]{64}$");
    }

    @Test
    void hashDiffersAcrossInputs() {
        String a = RefreshTokenHasher.hash("token-a");
        String b = RefreshTokenHasher.hash("token-b");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void hashOfKnownValueMatchesSha256() {
        // SHA-256 of "abc" is well known: ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad.
        assertThat(RefreshTokenHasher.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
