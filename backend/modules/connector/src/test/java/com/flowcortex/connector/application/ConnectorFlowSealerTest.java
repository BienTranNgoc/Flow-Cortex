package com.flowcortex.connector.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.connector.application.ConnectorFlowSealer.Flow;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConnectorFlowSealerTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID MEMBER = UUID.fromString("0d6a9d2e-4d3b-4a52-9d58-6a8f3f2f9c11");

    private static String newKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private static CredentialCipher cipher(String current, Map<String, String> keys) {
        return ConnectorEncryptionConfig.buildCipher(current, keys);
    }

    private final CredentialCipher cipher = cipher("k1", Map.of("k1", newKey()));
    private final ConnectorFlowSealer sealer = new ConnectorFlowSealer(cipher);

    private Flow flow() {
        return Flow.begin(MEMBER, Duration.ofMinutes(10), CLOCK);
    }

    @Test
    void sealedFlowOpensToTheSameMemberStateAndVerifier() {
        Flow flow = flow();

        Flow opened = sealer.open(sealer.seal(flow), NOW).orElseThrow();

        assertThat(opened.memberId()).isEqualTo(MEMBER);
        assertThat(opened.state()).isEqualTo(flow.state());
        assertThat(opened.verifier()).isEqualTo(flow.verifier());
        assertThat(opened.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
    }

    @Test
    void theSealedValueDoesNotRevealTheFlow() {
        Flow flow = flow();
        String sealed = sealer.seal(flow);
        Base64.Decoder decoder = Base64.getDecoder();
        StringBuilder decoded = new StringBuilder(sealed);
        for (String part : sealed.split("\\.")) {
            try {
                decoded.append(new String(decoder.decode(part), StandardCharsets.ISO_8859_1));
            } catch (IllegalArgumentException notBase64) {
                // the key id part is plain text
            }
        }

        assertThat(decoded.toString()).doesNotContain(MEMBER.toString()).doesNotContain(flow.state())
                .doesNotContain(flow.verifier());
        for (String secret : new String[] {MEMBER.toString(), flow.state(), flow.verifier()}) {
            assertThat(sealed).doesNotContain(Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Test
    void aFlippedCharacterOpensToNothing() {
        String sealed = sealer.seal(flow());
        String[] parts = sealed.split("\\.");
        char first = parts[3].charAt(0);
        parts[3] = (first == 'A' ? 'B' : 'A') + parts[3].substring(1);

        assertThat(sealer.open(String.join(".", parts), NOW)).isEmpty();
    }

    @Test
    void aFlowSealedUnderAnOlderKeyOpensAfterANewKeyBecomesCurrent() {
        String k1 = newKey();
        String sealed = new ConnectorFlowSealer(cipher("k1", Map.of("k1", k1))).seal(flow());
        Map<String, String> ring = new LinkedHashMap<>();
        ring.put("k1", k1);
        ring.put("k2", newKey());

        assertThat(new ConnectorFlowSealer(cipher("k2", ring)).open(sealed, NOW)).isPresent();
    }

    @Test
    void aFlowIsOpenUntilItsExpiryAndNotAtOrAfterIt() {
        Flow flow = flow();
        String sealed = sealer.seal(flow);

        assertThat(sealer.open(sealed, flow.expiresAt().minusSeconds(1))).isPresent();
        assertThat(sealer.open(sealed, flow.expiresAt())).isEmpty();
        assertThat(sealer.open(sealed, flow.expiresAt().plusSeconds(1))).isEmpty();
    }

    @Test
    void aCredentialSealedForARealMemberIsNotAFlow() {
        String credential = cipher.encrypt(MEMBER, "google", MEMBER + "|state|verifier|" + NOW.plusSeconds(600).getEpochSecond());

        assertThat(sealer.open(credential, NOW)).isEmpty();
    }

    @Test
    void nullBlankAndGarbageOpenToNothing() {
        assertThat(sealer.open(null, NOW)).isEmpty();
        assertThat(sealer.open("", NOW)).isEmpty();
        assertThat(sealer.open("   ", NOW)).isEmpty();
        assertThat(sealer.open("not-a-sealed-value", NOW)).isEmpty();
    }

    @Test
    void stateAndVerifierAreFreshAndTheChallengeIsTheS256OfTheVerifier() throws Exception {
        Flow first = flow();
        Flow second = flow();

        assertThat(first.state()).isNotEqualTo(second.state());
        assertThat(first.verifier()).isNotEqualTo(second.verifier()).isNotEqualTo(first.state());
        assertThat(Base64.getUrlDecoder().decode(first.state())).hasSize(32);
        assertThat(Base64.getUrlDecoder().decode(first.verifier())).hasSize(32);

        byte[] digest = MessageDigest.getInstance("SHA-256").digest(first.verifier().getBytes(StandardCharsets.US_ASCII));
        assertThat(first.codeChallenge()).isEqualTo(Base64.getUrlEncoder().withoutPadding().encodeToString(digest));
    }
}
