package com.flowcortex.user.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PkceTest {

    private static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    private static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

    @Test
    void theRfc7636AppendixBPairMatches() {
        assertThat(Pkce.matches(VERIFIER, CHALLENGE)).isTrue();
    }

    @Test
    void oneChangedCharacterDoesNotMatch() {
        assertThat(Pkce.matches("e" + VERIFIER.substring(1), CHALLENGE)).isFalse();
        assertThat(Pkce.matches(VERIFIER, "F" + CHALLENGE.substring(1))).isFalse();
    }

    @Test
    void aVerifierOutsideTheRfcAlphabetOrLengthIsRefused() {
        assertThat(Pkce.matches("a".repeat(42), CHALLENGE)).isFalse();
        assertThat(Pkce.matches("a".repeat(129), CHALLENGE)).isFalse();
        assertThat(Pkce.matches(VERIFIER.substring(0, 42) + "+", CHALLENGE)).isFalse();
        assertThat(Pkce.matches(null, CHALLENGE)).isFalse();
        assertThat(Pkce.matches(VERIFIER, null)).isFalse();
    }

    @Test
    void aValidChallengeIs43To128Base64UrlCharacters() {
        assertThat(Pkce.validChallenge(CHALLENGE)).isTrue();
        assertThat(Pkce.validChallenge("a".repeat(43))).isTrue();
        assertThat(Pkce.validChallenge("a".repeat(128))).isTrue();
        assertThat(Pkce.validChallenge("a".repeat(42))).isFalse();
        assertThat(Pkce.validChallenge("a".repeat(129))).isFalse();
        assertThat(Pkce.validChallenge(CHALLENGE.substring(0, 42) + "+")).isFalse();
        assertThat(Pkce.validChallenge(CHALLENGE.substring(0, 42) + "=")).isFalse();
        assertThat(Pkce.validChallenge(CHALLENGE.substring(0, 42) + ".")).isFalse();
        assertThat(Pkce.validChallenge(null)).isFalse();
    }
}
