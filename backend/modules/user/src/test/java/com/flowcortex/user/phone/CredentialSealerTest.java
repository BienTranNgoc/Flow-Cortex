package com.flowcortex.user.phone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.user.phone.CredentialSealer.CredentialUnreadableException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CredentialSealerTest {

    private static final String ACCOUNT = "vietguys_sms";
    private static final String TOKEN = "refresh-token-plaintext-0123456789";
    private static final String KEY_A = key((byte) 1);
    private static final String KEY_B = key((byte) 2);
    private static final String KEY_C = key((byte) 3);

    @Test
    void sealedTextIsNotThePlaintextAndOpensBack() {
        CredentialSealer sealer = new CredentialSealer(Map.of("k1", KEY_A), "k1");

        String sealed = sealer.seal(ACCOUNT, TOKEN);

        assertThat(sealed).startsWith("k1.").doesNotContain(TOKEN);
        assertThat(sealed.split("\\.", -1)).hasSize(4);
        assertThat(sealer.open(ACCOUNT, sealed)).isEqualTo(TOKEN);
    }

    @Test
    void twoSealsOfTheSameTextDiffer() {
        CredentialSealer sealer = new CredentialSealer(Map.of("k1", KEY_A), "k1");

        assertThat(sealer.seal(ACCOUNT, TOKEN)).isNotEqualTo(sealer.seal(ACCOUNT, TOKEN));
    }

    @Test
    void aTamperedCiphertextIsRefused() {
        CredentialSealer sealer = new CredentialSealer(Map.of("k1", KEY_A), "k1");
        String[] parts = sealer.seal(ACCOUNT, TOKEN).split("\\.", -1);
        byte[] ciphertext = Base64.getDecoder().decode(parts[3]);
        ciphertext[0] ^= 1;
        parts[3] = Base64.getEncoder().encodeToString(ciphertext);

        assertThatThrownBy(() -> sealer.open(ACCOUNT, String.join(".", parts)))
                .isInstanceOf(CredentialUnreadableException.class);
    }

    @Test
    void aCiphertextSealedForAnotherAccountIsRefused() {
        CredentialSealer sealer = new CredentialSealer(Map.of("k1", KEY_A), "k1");
        String sealed = sealer.seal(ACCOUNT, TOKEN);

        assertThatThrownBy(() -> sealer.open("another_account", sealed))
                .isInstanceOf(CredentialUnreadableException.class);
    }

    @Test
    void aKeyIdMissingFromTheRingIsRefused() {
        String sealed = new CredentialSealer(Map.of("k1", KEY_A), "k1").seal(ACCOUNT, TOKEN);
        CredentialSealer withoutK1 = new CredentialSealer(Map.of("k2", KEY_B), "k2");

        assertThatThrownBy(() -> withoutK1.open(ACCOUNT, sealed)).isInstanceOf(CredentialUnreadableException.class);
    }

    @Test
    void aSameIdWithAnotherKeyIsRefused() {
        String sealed = new CredentialSealer(Map.of("k1", KEY_A), "k1").seal(ACCOUNT, TOKEN);
        CredentialSealer otherKey = new CredentialSealer(Map.of("k1", KEY_B), "k1");

        assertThatThrownBy(() -> otherKey.open(ACCOUNT, sealed)).isInstanceOf(CredentialUnreadableException.class);
    }

    @Test
    void anOldKeyStillOpensAndNewSealsUseTheCurrentKey() {
        String oldSeal = new CredentialSealer(Map.of("k1", KEY_A), "k1").seal(ACCOUNT, TOKEN);
        CredentialSealer rotated = new CredentialSealer(Map.of("k1", KEY_A, "k2", KEY_B), "k2");

        assertThat(rotated.open(ACCOUNT, oldSeal)).isEqualTo(TOKEN);
        String newSeal = rotated.seal(ACCOUNT, TOKEN);
        assertThat(newSeal).startsWith("k2.");
        assertThat(rotated.open(ACCOUNT, newSeal)).isEqualTo(TOKEN);
    }

    @Test
    void aCurrentKeyIdNotInTheRingIsRefusedNamingTheProperty() {
        Map<String, String> ring = Map.of("k1", KEY_A);

        assertThatThrownBy(() -> new CredentialSealer(ring, "k2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("flowcortex.sms.vietguys.current-key-id");
        assertThatThrownBy(() -> new CredentialSealer(ring, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("flowcortex.sms.vietguys.current-key-id");
        assertThatThrownBy(() -> new CredentialSealer(Map.of(), "k1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("flowcortex.sms.vietguys.current-key-id");
    }

    @Test
    void aKeyThatIsNot32BytesIsRefusedNamingTheProperty() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new CredentialSealer(Map.of("k1", shortKey), "k1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("flowcortex.sms.vietguys.keys.k1")
                .hasMessageNotContaining(shortKey);
    }

    @Test
    void aKeyThatIsNotBase64OrIsBlankIsRefusedNamingTheProperty() {
        assertThatThrownBy(() -> new CredentialSealer(Map.of("k1", "not*base64*!"), "k1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("flowcortex.sms.vietguys.keys.k1")
                .hasMessageNotContaining("not*base64*!");
        assertThatThrownBy(() -> new CredentialSealer(Map.of("k1", ""), "k1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("flowcortex.sms.vietguys.keys.k1");
    }

    @Test
    void aKeyIdHoldingTheSeparatorIsRefused() {
        assertThatThrownBy(() -> new CredentialSealer(Map.of("k.1", KEY_A), "k.1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("flowcortex.sms.vietguys.keys.k.1");
    }

    @Test
    void malformedStoredTextIsUnreadable() {
        CredentialSealer sealer = new CredentialSealer(Map.of("k1", KEY_A, "k3", KEY_C), "k1");

        for (String stored : new String[] {null, "", "k1", "k1.a.b", "k1.!!.!!.!!", "k1..."}) {
            assertThatThrownBy(() -> sealer.open(ACCOUNT, stored)).as(String.valueOf(stored))
                    .isInstanceOf(CredentialUnreadableException.class);
        }
    }

    @Test
    void anUnreadableMessageHoldsNeitherTheTokenNorTheStoredText() {
        CredentialSealer sealer = new CredentialSealer(Map.of("k1", KEY_A), "k1");
        String sealed = sealer.seal(ACCOUNT, TOKEN);

        assertThatThrownBy(() -> sealer.open("another_account", sealed))
                .hasMessageNotContaining(TOKEN)
                .hasMessageNotContaining(sealed);
    }

    private static String key(byte fill) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, fill);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
