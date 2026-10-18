package com.flowcortex.user.phone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PhoneNumberHasherTest {

    private static final String SECRET = "phone-hash-secret-for-unit-tests-0123456789";
    private static final String PHONE = "+84912345678";

    @Test
    void theHashIsTheHexHmacSha256OfTheNumberUnderTheSecretNotItsPlainDigest() {
        String hash = hasher(SECRET, true).hash(PHONE);

        // openssl dgst -sha256 -hmac "$SECRET" over the number
        assertThat(hash).isEqualTo("b429decfa5782d3b94a522b18662039bde8d12bad9e9236d368c9a3e68fc281c");
        // sha256 over the number alone, which anyone can enumerate
        assertThat(hash).isNotEqualTo("ed644edf0566470f0b5a8c13c792fa8e8a489574da3aceec119b9456e870d396");
    }

    @Test
    void anotherSecretGivesAnotherHash() {
        assertThat(hasher(SECRET + "x", true).hash(PHONE)).isNotEqualTo(hasher(SECRET, true).hash(PHONE));
    }

    @Test
    void startupFailsWhenTheFeatureIsOnAndTheSecretIsMissing() {
        assertThatThrownBy(() -> hasher(null, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FLOWCORTEX_PHONE_HASH_SECRET")
                .hasMessageContaining("not set");
        assertThatThrownBy(() -> hasher("   ", true)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void startupFailsWhenTheFeatureIsOnAndTheSecretIsShorterThan32Bytes() {
        assertThatThrownBy(() -> hasher("x".repeat(31), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shorter than 32 bytes");
        assertThat(hasher("x".repeat(32), true).hash(PHONE)).hasSize(64);
    }

    @Test
    void withTheFeatureOffNoSecretIsNeededButNothingCanBeHashed() {
        PhoneNumberHasher hasher = hasher(null, false);

        assertThatThrownBy(() -> hasher.hash(PHONE)).isInstanceOf(IllegalStateException.class);
    }

    private static PhoneNumberHasher hasher(String secret, boolean enabled) {
        return new PhoneNumberHasher(new PhoneHashProperties(secret), enabled);
    }
}
