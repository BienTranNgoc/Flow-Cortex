package com.flowcortex.user.totp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.OptionalLong;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class TotpCodesTest {

    /** RFC 6238 Appendix B shared secret "12345678901234567890" in base32. */
    private static final String RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test
    void rfc6238Sha1VectorsTruncatedToSixDigits() {
        assertThat(TotpCodes.codeAt(RFC_SECRET, TotpCodes.stepOf(Instant.ofEpochSecond(59)))).isEqualTo("287082");
        assertThat(TotpCodes.codeAt(RFC_SECRET, TotpCodes.stepOf(Instant.ofEpochSecond(1111111109L))))
                .isEqualTo("081804");
        assertThat(TotpCodes.codeAt(RFC_SECRET, TotpCodes.stepOf(Instant.ofEpochSecond(1111111111L))))
                .isEqualTo("050471");
        assertThat(TotpCodes.codeAt(RFC_SECRET, TotpCodes.stepOf(Instant.ofEpochSecond(1234567890L))))
                .isEqualTo("005924");
        assertThat(TotpCodes.codeAt(RFC_SECRET, TotpCodes.stepOf(Instant.ofEpochSecond(2000000000L))))
                .isEqualTo("279037");
    }

    @Test
    void previousAndNextStepMatchAndReportTheirStep() {
        Instant now = Instant.ofEpochSecond(1234567890L);
        long step = TotpCodes.stepOf(now);

        assertThat(TotpCodes.match(RFC_SECRET, TotpCodes.codeAt(RFC_SECRET, step), now)).isEqualTo(OptionalLong.of(step));
        assertThat(TotpCodes.match(RFC_SECRET, TotpCodes.codeAt(RFC_SECRET, step - 1), now))
                .isEqualTo(OptionalLong.of(step - 1));
        assertThat(TotpCodes.match(RFC_SECRET, TotpCodes.codeAt(RFC_SECRET, step + 1), now))
                .isEqualTo(OptionalLong.of(step + 1));
    }

    @Test
    void twoStepsAwayDoesNotMatch() {
        Instant now = Instant.ofEpochSecond(1234567890L);
        long step = TotpCodes.stepOf(now);

        assertThat(TotpCodes.match(RFC_SECRET, TotpCodes.codeAt(RFC_SECRET, step - 2), now)).isEmpty();
        assertThat(TotpCodes.match(RFC_SECRET, TotpCodes.codeAt(RFC_SECRET, step + 2), now)).isEmpty();
    }

    @Test
    void spacesAreIgnoredAndOtherShapesRefused() {
        Instant now = Instant.ofEpochSecond(59);
        long step = TotpCodes.stepOf(now);

        assertThat(TotpCodes.match(RFC_SECRET, "287 082", now)).isEqualTo(OptionalLong.of(step));
        assertThat(TotpCodes.match(RFC_SECRET, "28708", now)).isEmpty();
        assertThat(TotpCodes.match(RFC_SECRET, "2870820", now)).isEmpty();
        assertThat(TotpCodes.match(RFC_SECRET, "28a082", now)).isEmpty();
        assertThat(TotpCodes.match(RFC_SECRET, "", now)).isEmpty();
        assertThat(TotpCodes.match(RFC_SECRET, null, now)).isEmpty();
    }

    @Test
    void base32RoundTrip() {
        byte[] decoded = TotpCodes.base32Decode("JBSWY3DPEHPK3PXP");

        assertThat(decoded).startsWith("Hello!".getBytes(StandardCharsets.US_ASCII))
                .endsWith((byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF);
        assertThat(TotpCodes.base32Encode(decoded)).isEqualTo("JBSWY3DPEHPK3PXP");
    }

    @Test
    void aSecretOutsideTheAlphabetIsRefused() {
        assertThatThrownBy(() -> TotpCodes.base32Decode("JBSWY3DPEHPK3PX1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aNewSecretIs32Base32Characters() {
        String secret = TotpCodes.newSecret(new SecureRandom());

        assertThat(secret).matches(Pattern.compile("[A-Z2-7]{32}"));
        assertThat(TotpCodes.base32Decode(secret)).hasSize(20);
        assertThat(TotpCodes.newSecret(new SecureRandom())).isNotEqualTo(secret);
    }
}
