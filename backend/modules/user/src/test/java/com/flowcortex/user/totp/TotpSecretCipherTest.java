package com.flowcortex.user.totp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.user.totp.TotpSecretCipher.TotpSecretUnreadableException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TotpSecretCipherTest {

    private static final String K1 = key((byte) 1);
    private static final String K2 = key((byte) 2);
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final String SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    private static String key(byte fill) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, fill);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static TotpProperties properties(Map<String, String> keys, String current) {
        return new TotpProperties(keys, current, true, 10, Duration.ofMinutes(15), Duration.ofMinutes(5), 5, "Nodi",
                "");
    }

    private static TotpSecretCipher cipher(String current) {
        return new TotpSecretCipher(properties(Map.of("k1", K1, "k2", K2), current));
    }

    @Test
    void roundTripForItsAccount() {
        TotpSecretCipher cipher = cipher("k1");

        String stored = cipher.encrypt(ALICE, SECRET);

        assertThat(stored).startsWith("k1.").doesNotContain(SECRET);
        assertThat(stored.split("\\.")).hasSize(4);
        assertThat(cipher.decrypt(ALICE, stored)).isEqualTo(SECRET);
        assertThat(cipher.encrypt(ALICE, SECRET)).isNotEqualTo(stored);
    }

    @Test
    void anotherAccountCannotOpenIt() {
        TotpSecretCipher cipher = cipher("k1");
        String stored = cipher.encrypt(ALICE, SECRET);

        assertThatThrownBy(() -> cipher.decrypt(BOB, stored)).isInstanceOf(TotpSecretUnreadableException.class);
    }

    @Test
    void aTamperedTagIsRefused() {
        TotpSecretCipher cipher = cipher("k1");
        String[] parts = cipher.encrypt(ALICE, SECRET).split("\\.");
        byte[] tag = Base64.getDecoder().decode(parts[2]);
        tag[0] ^= 1;
        String tampered = String.join(".", parts[0], parts[1], Base64.getEncoder().encodeToString(tag), parts[3]);

        assertThatThrownBy(() -> cipher.decrypt(ALICE, tampered)).isInstanceOf(TotpSecretUnreadableException.class);
    }

    @Test
    void malformedStoredValuesAreRefused() {
        TotpSecretCipher cipher = cipher("k1");

        assertThatThrownBy(() -> cipher.decrypt(ALICE, null)).isInstanceOf(TotpSecretUnreadableException.class);
        assertThatThrownBy(() -> cipher.decrypt(ALICE, "k1.only.three")).isInstanceOf(TotpSecretUnreadableException.class);
        assertThatThrownBy(() -> cipher.decrypt(ALICE, "k1.!!.!!.!!")).isInstanceOf(TotpSecretUnreadableException.class);
    }

    @Test
    void anOldKeyIdStillOpens() {
        String underK1 = cipher("k1").encrypt(ALICE, SECRET);
        TotpSecretCipher rotated = cipher("k2");

        assertThat(rotated.decrypt(ALICE, underK1)).isEqualTo(SECRET);
        assertThat(rotated.encrypt(ALICE, SECRET)).startsWith("k2.");
    }

    @Test
    void anUnknownKeyIdIsRefused() {
        String underK1 = cipher("k1").encrypt(ALICE, SECRET);
        TotpSecretCipher onlyK2 = new TotpSecretCipher(properties(Map.of("k2", K2), "k2"));

        assertThatThrownBy(() -> onlyK2.decrypt(ALICE, underK1)).isInstanceOf(TotpSecretUnreadableException.class);
    }

    @Test
    void recoveryHashIsStablePerKeyAndDiffersAcrossKeys() {
        TotpSecretCipher cipher = cipher("k1");

        String first = cipher.recoveryHash("k1", "ABCDE23456");

        assertThat(first).matches("[0-9a-f]{64}");
        assertThat(cipher.recoveryHash("k1", "ABCDE23456")).isEqualTo(first);
        assertThat(cipher("k2").recoveryHash("k1", "ABCDE23456")).isEqualTo(first);
        assertThat(cipher.recoveryHash("k2", "ABCDE23456")).isNotEqualTo(first);
        assertThat(cipher.recoveryHash("k1", "ABCDE23457")).isNotEqualTo(first);
        assertThatThrownBy(() -> cipher.recoveryHash("k9", "ABCDE23456")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theRingIsExposedForRecoveryChecks() {
        TotpSecretCipher cipher = cipher("k2");

        assertThat(cipher.currentKeyId()).isEqualTo("k2");
        assertThat(cipher.keyIds()).containsExactlyInAnyOrder("k1", "k2");
    }

    @Test
    void aShortOrNonBase64KeyFailsConstruction() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new TotpSecretCipher(properties(Map.of("k1", shortKey), "k1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("flowcortex.auth.totp.keys.k1")
                .hasMessageNotContaining(shortKey);
        assertThatThrownBy(() -> new TotpSecretCipher(properties(Map.of("k1", "not base64 !!"), "k1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("flowcortex.auth.totp.keys.k1");
    }

    @Test
    void anEmptyRingOrAMissingCurrentKeyFailsConstruction() {
        assertThatThrownBy(() -> new TotpSecretCipher(properties(Map.of(), "k1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("flowcortex.auth.totp.keys");
        assertThatThrownBy(() -> new TotpSecretCipher(properties(Map.of("k1", K1), "k2")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("current-key-id");
    }

    @Test
    void aKeyIdThatCannotLiveInTheStoredFormatFailsConstruction() {
        assertThatThrownBy(() -> new TotpSecretCipher(properties(Map.of("k.1", K1), "k.1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("k.1");
    }

    @Test
    void blankKeyEntriesAreDroppedBeforeValidation() {
        TotpProperties blank = properties(Map.of("k1", ""), "k1");
        TotpProperties padded = properties(Map.of("k1", K1, "k2", "  "), "k1");

        assertThat(blank.keys()).isEmpty();
        assertThat(padded.keys()).containsOnlyKeys("k1");

        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        assertThat(validator.validate(blank)).extracting(violation -> violation.getPropertyPath().toString())
                .contains("keys");
        assertThat(validator.validate(padded)).isEmpty();
    }

    @Test
    void aCurrentKeyOutsideTheRingIsAViolation() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

        assertThat(validator.validate(properties(Map.of("k1", K1), "k2"))).hasSize(1);
    }

    @Test
    void aLockoutOutsideItsRangeIsRefused() {
        assertThatThrownBy(() -> new TotpProperties(Map.of("k1", K1), "k1", true, 10, Duration.ZERO,
                Duration.ofMinutes(5), 5, "Nodi", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TotpProperties(Map.of("k1", K1), "k1", true, 10, Duration.ofMinutes(1441),
                Duration.ofMinutes(5), 5, "Nodi", "")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aDevKeyFillsAnEmptyRingUnderTheCurrentIdAndNeverReplacesAConfiguredKey() {
        TotpProperties empty = new TotpProperties(Map.of("k1", ""), "k1", true, 10, Duration.ofMinutes(15),
                Duration.ofMinutes(5), 5, "Nodi", K2);
        TotpProperties configured = new TotpProperties(Map.of("k1", K1), "k1", true, 10, Duration.ofMinutes(15),
                Duration.ofMinutes(5), 5, "Nodi", K2);

        assertThat(empty.keys()).isEqualTo(Map.of("k1", K2));
        assertThat(configured.keys()).isEqualTo(Map.of("k1", K1));
    }
}
