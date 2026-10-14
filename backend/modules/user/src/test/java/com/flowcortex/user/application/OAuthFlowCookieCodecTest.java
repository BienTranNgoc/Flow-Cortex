package com.flowcortex.user.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class OAuthFlowCookieCodecTest {

    private static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final SecretKey KEY = key("a-signing-secret-of-at-least-32-bytes!");

    private final OAuthFlowCookieCodec codec = new OAuthFlowCookieCodec(KEY, CLOCK);

    private static SecretKey key(String secret) {
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    private static OAuthFlowState flow() {
        return OAuthFlowState.begin("/runs?tab=2", true, Duration.ofMinutes(10), CLOCK);
    }

    @Test
    void aSealedFlowOpensToTheSameValues() {
        OAuthFlowState flow = flow();

        assertThat(codec.open(codec.seal(flow))).contains(flow);
    }

    @Test
    void everyFlowGetsItsOwnStateVerifierAndNonce() {
        OAuthFlowState first = flow();
        OAuthFlowState second = flow();

        assertThat(first.state()).isNotEqualTo(second.state());
        assertThat(first.codeVerifier()).isNotEqualTo(second.codeVerifier()).hasSizeGreaterThanOrEqualTo(43);
        assertThat(first.nonce()).isNotEqualTo(second.nonce());
    }

    @Test
    void theChallengeIsTheS256HashOfTheVerifier() {
        OAuthFlowState flow = new OAuthFlowState("s", "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk", "n", "/", false,
                NOW.plusSeconds(60));

        // RFC 7636 appendix B
        assertThat(flow.codeChallenge()).isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
    }

    @Test
    void aTamperedPayloadIsRejected() {
        String[] parts = codec.seal(flow()).split("\\.");
        parts[4] = parts[4].equals("1") ? "0" : "1";

        assertThat(codec.open(String.join(".", parts))).isEmpty();
    }

    @Test
    void aTamperedSignatureIsRejected() {
        String sealed = codec.seal(flow());
        int signatureStart = sealed.lastIndexOf('.') + 1;
        char first = sealed.charAt(signatureStart);
        String forged = sealed.substring(0, signatureStart) + (first == 'A' ? 'B' : 'A')
                + sealed.substring(signatureStart + 1);

        assertThat(codec.open(forged)).isEmpty();
    }

    @Test
    void aCookieSealedWithAnotherKeyIsRejected() {
        String sealed = new OAuthFlowCookieCodec(key("another-secret-of-at-least-32-bytes!!"), CLOCK).seal(flow());

        assertThat(codec.open(sealed)).isEmpty();
    }

    @Test
    void anExpiredFlowIsRejected() {
        String sealed = codec.seal(flow());
        OAuthFlowCookieCodec later = new OAuthFlowCookieCodec(KEY,
                Clock.fixed(NOW.plus(Duration.ofMinutes(10)), ZoneOffset.UTC));

        assertThat(later.open(sealed)).isEmpty();
    }

    @Test
    void garbageIsRejectedWithoutThrowing() {
        assertThat(codec.open("")).isEmpty();
        assertThat(codec.open("a.b.c")).isEmpty();
        assertThat(codec.open("a.b.c.d.e.f.g")).isEmpty();
        assertThat(codec.open("......")).isEmpty();
        assertThat(codec.open(null)).isEmpty();
    }
}
