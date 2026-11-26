package com.flowcortex.user.phone;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.phone.VietguysTokenPolicy.Renewal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class VietguysTokenPolicyTest {

    private static final Instant NOW = Instant.parse("2026-11-15T08:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void renewalIsNotDueWithThreeHoursLeft() {
        assertThat(VietguysTokenPolicy.renewalDue(NOW.plus(Duration.ofHours(3)), NOW)).isFalse();
    }

    @Test
    void renewalIsNotDueAtExactlyTwoHoursLeft() {
        assertThat(VietguysTokenPolicy.renewalDue(NOW.plus(Duration.ofHours(2)), NOW)).isFalse();
    }

    @Test
    void renewalIsDueWithOneHourFiftyNineMinutesLeft() {
        assertThat(VietguysTokenPolicy.renewalDue(NOW.plus(Duration.ofMinutes(119)), NOW)).isTrue();
    }

    @Test
    void renewalIsDueWhenThereIsNoAccessToken() {
        assertThat(VietguysTokenPolicy.renewalDue(null, NOW)).isTrue();
    }

    @Test
    void renewalIsDueWhenTheAccessTokenHasAlreadyLapsed() {
        assertThat(VietguysTokenPolicy.renewalDue(NOW.minusSeconds(1), NOW)).isTrue();
    }

    @Test
    void theSeedIsWrittenWhenNothingIsStored() {
        assertThat(VietguysTokenPolicy.seedWrites(null, VietguysTokenPolicy.fingerprint("seed-a"))).isTrue();
    }

    @Test
    void theSeedIsNotWrittenWhenItsFingerprintIsTheStoredOne() {
        String fingerprint = VietguysTokenPolicy.fingerprint("seed-a");

        assertThat(VietguysTokenPolicy.seedWrites(fingerprint, VietguysTokenPolicy.fingerprint("seed-a"))).isFalse();
    }

    @Test
    void theSeedIsWrittenWhenItsFingerprintDiffers() {
        String stored = VietguysTokenPolicy.fingerprint("seed-a");

        assertThat(VietguysTokenPolicy.seedWrites(stored, VietguysTokenPolicy.fingerprint("seed-b"))).isTrue();
    }

    @Test
    void theFingerprintIsTheHexSha256OfTheSeed() {
        // printf abc | openssl dgst -sha256
        assertThat(VietguysTokenPolicy.fingerprint("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void theExpiryIsReadInMilliseconds() {
        Instant expiry = NOW.plus(Duration.ofHours(24));

        JsonNode answer = json("{\"error\":0,\"data\":{\"expired_at\":" + expiry.toEpochMilli() + "}}");

        assertThat(VietguysTokenPolicy.expiryFrom(answer, NOW)).isEqualTo(expiry);
    }

    @Test
    void anAbsentExpiryGivesAFallbackThatIsStillReusable() {
        Instant fallback = NOW.plus(VietguysTokenPolicy.RENEW_WITHIN).plus(Duration.ofHours(1));

        assertThat(VietguysTokenPolicy.expiryFrom(json("{\"error\":0,\"data\":{}}"), NOW)).isEqualTo(fallback);
        assertThat(VietguysTokenPolicy.expiryFrom(json("{\"error\":0}"), NOW)).isEqualTo(fallback);
        assertThat(VietguysTokenPolicy.renewalDue(VietguysTokenPolicy.expiryFrom(json("{\"error\":0}"), NOW), NOW))
                .isFalse();
    }

    @Test
    void everyUnreadableExpiryGivesAFallbackThatIsNotDue() {
        for (String expiredAt : new String[] {"\"soon\"", "null", "0", "-5", "\"-5\"", "\"\"", "1.5", "true", "[]",
                "99999999999999999999"}) {
            JsonNode answer = json("{\"data\":{\"expired_at\":" + expiredAt + "}}");

            assertThat(VietguysTokenPolicy.readableExpiry(answer, NOW)).as(expiredAt).isEmpty();
            assertThat(VietguysTokenPolicy.renewalDue(VietguysTokenPolicy.expiryFrom(answer, NOW), NOW))
                    .as(expiredAt).isFalse();
        }
    }

    @Test
    void aNumericStringExpiryIsReadInMilliseconds() {
        Instant expiry = NOW.plus(Duration.ofHours(24));

        assertThat(VietguysTokenPolicy.expiryFrom(
                json("{\"data\":{\"expired_at\":\" " + expiry.toEpochMilli() + " \"}}"), NOW)).isEqualTo(expiry);
    }

    @Test
    void anExpiryInEpochSecondsIsScaledToMilliseconds() {
        Instant expiry = NOW.plus(Duration.ofHours(24));

        assertThat(VietguysTokenPolicy.expiryFrom(
                json("{\"data\":{\"expired_at\":" + expiry.getEpochSecond() + "}}"), NOW)).isEqualTo(expiry);
        assertThat(VietguysTokenPolicy.expiryFrom(
                json("{\"data\":{\"expired_at\":\"" + expiry.getEpochSecond() + "\"}}"), NOW)).isEqualTo(expiry);
    }

    @Test
    void aPastExpiryIsUnreadable() {
        JsonNode answer = json("{\"data\":{\"expired_at\":" + NOW.minusSeconds(60).toEpochMilli() + "}}");

        assertThat(VietguysTokenPolicy.readableExpiry(answer, NOW)).isEmpty();
        assertThat(VietguysTokenPolicy.expiryFrom(answer, NOW))
                .isEqualTo(NOW.plus(VietguysTokenPolicy.RENEW_WITHIN).plus(Duration.ofHours(1)));
    }

    @Test
    void anExpiryMoreThanThirtyDaysAheadIsUnreadable() {
        Instant limit = NOW.plus(Duration.ofDays(30));

        assertThat(VietguysTokenPolicy.readableExpiry(
                json("{\"data\":{\"expired_at\":" + limit.toEpochMilli() + "}}"), NOW)).contains(limit);
        assertThat(VietguysTokenPolicy.readableExpiry(
                json("{\"data\":{\"expired_at\":" + limit.plusMillis(1).toEpochMilli() + "}}"), NOW)).isEmpty();
    }

    @Test
    void theFallbackAlwaysOutlastsTheRenewalWindow() {
        assertThat(VietguysTokenPolicy.FALLBACK_LIFETIME).isGreaterThan(VietguysTokenPolicy.RENEW_WITHIN);
    }

    @Test
    void anAnswerWithBothTokensIsAccepted() {
        assertThat(VietguysTokenPolicy.renewalAccepted(json(
                "{\"error\":0,\"data\":{\"access_token\":\"a\",\"refresh_token\":\"r\"}}"))).isEqualTo(Renewal.RENEWED);
        assertThat(VietguysTokenPolicy.renewalAccepted(json(
                "{\"error\":\"0\",\"data\":{\"access_token\":\"a\",\"refresh_token\":\"r\"}}"))).isEqualTo(Renewal.RENEWED);
    }

    @Test
    void anAnswerWithoutARefreshTokenFails() {
        assertThat(VietguysTokenPolicy.renewalAccepted(json("{\"error\":0,\"data\":{\"access_token\":\"a\"}}")))
                .isEqualTo(Renewal.FAILED);
        assertThat(VietguysTokenPolicy.renewalAccepted(json(
                "{\"error\":0,\"data\":{\"access_token\":\"a\",\"refresh_token\":\"\"}}"))).isEqualTo(Renewal.FAILED);
        assertThat(VietguysTokenPolicy.renewalAccepted(json(
                "{\"error\":0,\"data\":{\"access_token\":\" \",\"refresh_token\":\"r\"}}"))).isEqualTo(Renewal.FAILED);
        assertThat(VietguysTokenPolicy.renewalAccepted(json(
                "{\"error\":0,\"data\":{\"access_token\":1,\"refresh_token\":\"r\"}}"))).isEqualTo(Renewal.FAILED);
    }

    @Test
    void errorMinusFifteenMeansTheRefreshTokenIsRevoked() {
        assertThat(VietguysTokenPolicy.renewalAccepted(json("{\"error\":-15}"))).isEqualTo(Renewal.REVOKED);
        assertThat(VietguysTokenPolicy.renewalAccepted(json("{\"error\":\"-15\"}"))).isEqualTo(Renewal.REVOKED);
    }

    @Test
    void anyOtherAnswerFails() {
        assertThat(VietguysTokenPolicy.renewalAccepted(json("{\"error\":1}"))).isEqualTo(Renewal.FAILED);
        assertThat(VietguysTokenPolicy.renewalAccepted(json("{\"error\":-5}"))).isEqualTo(Renewal.FAILED);
        assertThat(VietguysTokenPolicy.renewalAccepted(json("{}"))).isEqualTo(Renewal.FAILED);
        assertThat(VietguysTokenPolicy.renewalAccepted(json("[]"))).isEqualTo(Renewal.FAILED);
        assertThat(VietguysTokenPolicy.renewalAccepted(json("null"))).isEqualTo(Renewal.FAILED);
        assertThat(VietguysTokenPolicy.renewalAccepted(null)).isEqualTo(Renewal.FAILED);
    }

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }
}
