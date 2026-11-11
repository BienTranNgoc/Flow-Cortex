package com.flowcortex.support.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.support.config.SupportProperties;
import com.flowcortex.support.web.SupportProblemException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AskerLimitsTest {

    private static final String SECRET = "support-hash-secret-for-unit-tests-0123456789";

    @Test
    void theEleventhQuestionInAMinuteIsRefusedWithTheNextAllowedTime() {
        Instant now = Instant.parse("2026-10-19T08:20:00Z");
        MinuteLimiter limiter = new MinuteLimiter(Clock.fixed(now, ZoneOffset.UTC));
        for (int i = 0; i < MinuteLimiter.PER_MINUTE; i++) {
            limiter.acquire("member:a");
        }

        assertThatThrownBy(() -> limiter.acquire("member:a"))
                .isInstanceOfSatisfying(SupportProblemException.class, e -> {
                    assertThat(e.code()).isEqualTo("assistant-quota");
                    assertThat((Instant) e.properties().get("retryAt")).isAfter(now)
                            .isBeforeOrEqualTo(now.plusSeconds(60));
                });
        limiter.acquire("member:b");
    }

    @Test
    void networkAndSessionBucketsAreSeparateAndTheNetworkOneRefusesTheEleventhQuestion() {
        Instant now = Instant.parse("2026-10-19T08:20:00Z");
        MinuteLimiter limiter = new MinuteLimiter(Clock.fixed(now, ZoneOffset.UTC));
        for (int i = 0; i < MinuteLimiter.PER_MINUTE; i++) {
            limiter.acquireNetwork("guest-ip:a");
        }

        assertThatThrownBy(() -> limiter.acquireNetwork("guest-ip:a"))
                .isInstanceOfSatisfying(SupportProblemException.class,
                        e -> assertThat(e.code()).isEqualTo("assistant-quota"));
        limiter.acquireNetwork("guest-ip:b");
        limiter.acquire("guest-ip:a");
    }

    @Test
    void aFloodOfSessionKeysDoesNotResetANetworkBucket() {
        MinuteLimiter limiter = new MinuteLimiter(Clock.fixed(Instant.parse("2026-10-19T08:20:00Z"), ZoneOffset.UTC));
        for (int i = 0; i < MinuteLimiter.PER_MINUTE; i++) {
            limiter.acquireNetwork("guest-ip:a");
        }
        for (int i = 0; i < 150_000; i++) {
            limiter.acquire("guest-session:" + i);
        }

        assertThatThrownBy(() -> limiter.acquireNetwork("guest-ip:a")).isInstanceOf(SupportProblemException.class);
    }

    @Test
    void anIpv6NetworkIsKeyedOnItsSixtyFourBitPrefix() {
        GuestKeys keys = new GuestKeys(properties(true), SECRET);
        String network = keys.guest("s", "2001:db8:1:2:aaaa:bbbb:cccc:dddd").networkHash();

        assertThat(keys.guest("t", "2001:db8:1:2:1111:2222:3333:4444").networkHash()).isEqualTo(network);
        assertThat(keys.guest("t", "2001:0db8:0001:0002::1").networkHash()).isEqualTo(network);
        assertThat(keys.guest("t", "2001:db8:1:3::1").networkHash()).isNotEqualTo(network);
        assertThat(keys.guest("t", "::ffff:203.0.113.9").networkHash())
                .isEqualTo(keys.guest("t", "203.0.113.9").networkHash());
    }

    @Test
    void aDayEndsAtMidnightInVietnam() {
        assertThat(QuotaService.resetsAt(LocalDate.of(2026, 10, 19))).isEqualTo(Instant.parse("2026-10-19T17:00:00Z"));
    }

    @Test
    void onlyAVersionFourUuidIsASession() {
        String session = "3f2b8c1e-9d4a-4b7e-8f21-6c5d4e3a2b10";

        assertThat(AssistantController.validSession(session.toUpperCase())).isEqualTo(session);
        for (String bad : new String[] {null, "", "6ba7b810-9dad-11d1-80b4-00c04fd430c8", "1-1-1-1-1",
                "3f2b8c1e-9d4a-4b7e-8f21-6c5d4e3a2b1z"}) {
            assertThatThrownBy(() -> AssistantController.validSession(bad))
                    .isInstanceOfSatisfying(SupportProblemException.class,
                            e -> assertThat(e.code()).isEqualTo("validation-failed"));
        }
    }

    @Test
    void identifiersAreKeptOnlyAsKeyedHashes() {
        Asker.Guest guest = new GuestKeys(properties(true), SECRET).guest("s", "127.0.0.1");

        assertThat(guest.networkHash()).matches("[0-9a-f]{64}")
                .isNotEqualTo("12ca17b49af2289436f303e0166030a21e525d266e209267433801a8fd4071a0")
                .isNotEqualTo(new GuestKeys(properties(true), SECRET + "x").guest("s", "127.0.0.1").networkHash());
        assertThat(guest.subjectKey()).isEqualTo("guest-session:" + guest.sessionHash());
        assertThat(guest.networkKey()).isEqualTo("guest-ip:" + guest.networkHash());
    }

    @Test
    void theAssistantOnNeedsAHashSecretOfThirtyTwoBytes() {
        for (String weak : new String[] {null, " ", "short"}) {
            assertThatThrownBy(() -> new GuestKeys(properties(true), weak))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("FLOWCORTEX_SUPPORT_HASH_SECRET");
        }
        assertThat(new GuestKeys(properties(false), "").configured()).isFalse();
    }

    private static SupportProperties properties(boolean assistantEnabled) {
        return new SupportProperties(true, "", new SupportProperties.Assistant(assistantEnabled,
                "gemini-test", 2000, 50, 10, 100), new SupportProperties.Lifecycle(true, 7, true, 0, true, 24));
    }
}
