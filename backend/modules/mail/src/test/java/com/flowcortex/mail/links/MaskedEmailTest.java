package com.flowcortex.mail.links;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MaskedEmailTest {

    @Test
    void theLocalPartKeepsItsFirstCharacter() {
        assertThat(MaskedEmail.of("alice@example.com")).isEqualTo("a***@example.com");
    }

    @Test
    void aOneCharacterLocalPartIsMaskedTheSameWay() {
        assertThat(MaskedEmail.of("a@example.com")).isEqualTo("a***@example.com");
    }

    @Test
    void aMalformedAddressNeverThrowsAndNeverEchoes() {
        assertThat(MaskedEmail.of("no-at-sign")).isEqualTo("***");
        assertThat(MaskedEmail.of("@example.com")).isEqualTo("***");
        assertThat(MaskedEmail.of("alice@")).isEqualTo("***");
        assertThat(MaskedEmail.of("")).isEqualTo("***");
        assertThat(MaskedEmail.of(null)).isEqualTo("***");
    }
}
