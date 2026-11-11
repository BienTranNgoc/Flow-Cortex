package com.flowcortex.connector.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.connector.api.CredentialUnreadableException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CredentialCipherTest {

    private static final UUID MEMBER = UUID.randomUUID();
    private static final String TOKEN = "tok-123456";

    private static String newKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private static CredentialCipher cipher(String current, Map<String, String> keys) {
        return ConnectorEncryptionConfig.buildCipher(current, keys);
    }

    private static CredentialCipher cipher() {
        return cipher("k1", Map.of("k1", newKey()));
    }

    @Test
    void roundTripsAndSeparatesIdFromParts() {
        CredentialCipher cipher = cipher();
        String stored = cipher.encrypt(MEMBER, "google", TOKEN);
        assertThat(stored.split("\\.")).hasSize(4).startsWith("k1");
        assertThat(cipher.decrypt(MEMBER, "google", stored)).isEqualTo(TOKEN);
    }

    @Test
    void samePlaintextTwiceGivesDifferentCiphertext() {
        CredentialCipher cipher = cipher();
        assertThat(cipher.encrypt(MEMBER, "google", TOKEN)).isNotEqualTo(cipher.encrypt(MEMBER, "google", TOKEN));
    }

    @Test
    void storedValueHoldsNeitherPlaintextNorItsBase64() {
        String stored = cipher().encrypt(MEMBER, "google", TOKEN);
        String base64 = Base64.getEncoder().encodeToString(TOKEN.getBytes(StandardCharsets.UTF_8));
        assertThat(stored).doesNotContain(TOKEN).doesNotContain(base64);
    }

    @Test
    void flippedByteIsRefused() {
        CredentialCipher cipher = cipher();
        String[] parts = cipher.encrypt(MEMBER, "google", TOKEN).split("\\.");
        byte[] ct = Base64.getDecoder().decode(parts[3]);
        ct[0] ^= 1;
        parts[3] = Base64.getEncoder().encodeToString(ct);
        assertUnreadable(cipher, String.join(".", parts));
    }

    @Test
    void malformedValuesAreRefused() {
        CredentialCipher cipher = cipher();
        assertUnreadable(cipher, "k1.only.three");
        assertUnreadable(cipher, "k1.!!!.@@@.###");
        assertUnreadable(cipher, "");
        assertUnreadable(cipher, "k1.AAAA.AAAA.AAAA.AAAA");
    }

    @Test
    void differentKeyUnderTheSameIdIsRefused() {
        String stored = cipher("k1", Map.of("k1", newKey())).encrypt(MEMBER, "google", TOKEN);
        assertUnreadable(cipher("k1", Map.of("k1", newKey())), stored);
    }

    @Test
    void unknownKeyIdIsRefused() {
        String key = newKey();
        String stored = cipher("k9", Map.of("k9", key, "k1", key)).encrypt(MEMBER, "google", TOKEN);
        assertUnreadable(cipher("k1", Map.of("k1", key)), stored);
    }

    @Test
    void ciphertextMovedToAnotherMemberOrProviderIsRefused() {
        CredentialCipher cipher = cipher();
        String stored = cipher.encrypt(MEMBER, "google", TOKEN);
        assertThatThrownBy(() -> cipher.decrypt(UUID.randomUUID(), "google", stored))
                .isInstanceOf(CredentialUnreadableException.class);
        assertThatThrownBy(() -> cipher.decrypt(MEMBER, "telegram", stored))
                .isInstanceOf(CredentialUnreadableException.class);
    }

    @Test
    void oldKeyStillDecryptsAfterTheCurrentIdChanges() {
        String k1 = newKey();
        String stored = cipher("k1", Map.of("k1", k1)).encrypt(MEMBER, "google", TOKEN);
        Map<String, String> ring = new LinkedHashMap<>();
        ring.put("k1", k1);
        ring.put("k2", newKey());
        CredentialCipher rotated = cipher("k2", ring);
        assertThat(rotated.decrypt(MEMBER, "google", stored)).isEqualTo(TOKEN);
        assertThat(rotated.encrypt(MEMBER, "google", TOKEN)).startsWith("k2.");
    }

    private static void assertUnreadable(CredentialCipher cipher, String stored) {
        assertThatThrownBy(() -> cipher.decrypt(MEMBER, "google", stored))
                .isInstanceOf(CredentialUnreadableException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(TOKEN));
    }
}
