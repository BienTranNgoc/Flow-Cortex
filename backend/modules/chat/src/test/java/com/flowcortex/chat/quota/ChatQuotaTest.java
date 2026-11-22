package com.flowcortex.chat.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.chat.config.ChatProperties;
import com.flowcortex.chat.web.ChatProblemException;
import com.flowcortex.shared.ratelimit.InMemoryRateLimitStore;
import com.flowcortex.shared.ratelimit.RateLimitTelemetry;
import com.flowcortex.shared.ratelimit.RateLimiters;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ChatQuotaTest {

    private static final ZoneId HCM = ZoneId.of("Asia/Ho_Chi_Minh");

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-07T05:00:00Z"));
    private final MemoryLedger ledger = new MemoryLedger();

    private ChatQuota quota(int perMinute) {
        ChatProperties properties = new ChatProperties(true, "model", new ChatProperties.Quota(100, 5000, perMinute),
                Duration.ofSeconds(90), Duration.ofSeconds(30), 4, 8, 32, 8192, 1024);
        return new ChatQuota(ledger, minuteLimiter(properties), properties, clock);
    }

    private ChatMinuteLimiter minuteLimiter(ChatProperties properties) {
        RateLimiters limiters = new RateLimiters(List.of(ChatRateLimits.definition(properties)),
                new InMemoryRateLimitStore(clock), new RateLimitTelemetry(new SimpleMeterRegistry(), clock));
        return new ChatMinuteLimiter(limiters, clock);
    }

    @Test
    void aMemberMaySendOneHundredAMessagesADayAndTheNextIsRefusedUntilMidnightInHoChiMinh() {
        ChatQuota quota = quota(1000);
        UUID member = UUID.randomUUID();
        for (int i = 0; i < 100; i++) {
            quota.charge(member);
        }

        assertThatThrownBy(() -> quota.charge(member)).isInstanceOfSatisfying(ChatProblemException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(e.code()).isEqualTo("chat-quota");
            assertThat(e.properties().get("resumeAt"))
                    .isEqualTo(LocalDate.of(2026, 11, 8).atStartOfDay(HCM).toInstant());
        });
        assertThat(ledger.count(LocalDate.of(2026, 11, 7), "member:" + member)).isEqualTo(100);
    }

    @Test
    void aMemberMaySendTenAMinuteAndTheNextIsRefusedUntilTheNextMinuteWithNothingCharged() {
        ChatQuota quota = quota(10);
        UUID member = UUID.randomUUID();
        for (int i = 0; i < 10; i++) {
            quota.charge(member);
        }

        assertThatThrownBy(() -> quota.charge(member)).isInstanceOfSatisfying(ChatProblemException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(e.code()).isEqualTo("chat-quota");
            Instant resumeAt = (Instant) e.properties().get("resumeAt");
            assertThat(resumeAt).isAfter(clock.instant()).isBeforeOrEqualTo(clock.instant().plusSeconds(60));
        });
        assertThat(ledger.count(LocalDate.of(2026, 11, 7), "member:" + member)).isEqualTo(10);
        assertThat(ledger.count(LocalDate.of(2026, 11, 7), "global")).isEqualTo(10);
    }

    @Test
    void theWholeSystemAnswersFiveThousandAMessagesADayWhateverTheMember() {
        ChatQuota quota = quota(1000);
        for (int i = 0; i < 5000; i++) {
            quota.charge(UUID.nameUUIDFromBytes(Integer.toString(i / 50).getBytes()));
        }
        UUID fresh = UUID.randomUUID();

        assertThatThrownBy(() -> quota.charge(fresh)).isInstanceOfSatisfying(ChatProblemException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(e.code()).isEqualTo("chat-quota");
        });
        assertThat(ledger.count(LocalDate.of(2026, 11, 7), "member:" + fresh)).isZero();
    }

    @Test
    void aDayEndsAtMidnightInHoChiMinhNotInUtc() {
        ChatQuota quota = quota(1000);
        UUID member = UUID.randomUUID();
        clock.set(Instant.parse("2026-11-07T16:59:59Z"));
        LocalDate before = quota.charge(member);
        clock.set(Instant.parse("2026-11-07T17:00:00Z"));
        LocalDate after = quota.charge(member);

        assertThat(before).isEqualTo(LocalDate.of(2026, 11, 7));
        assertThat(after).isEqualTo(LocalDate.of(2026, 11, 8));
        assertThat(ledger.count(before, "member:" + member)).isEqualTo(1);
        assertThat(ledger.count(after, "member:" + member)).isEqualTo(1);
    }

    @Test
    void aRefundGivesBackOneToTheDayItWasChargedOnEvenAfterMidnight() {
        ChatQuota quota = quota(1000);
        UUID member = UUID.randomUUID();
        clock.set(Instant.parse("2026-11-07T16:59:59Z"));
        LocalDate chargedOn = quota.charge(member);
        quota.charge(member);
        clock.set(Instant.parse("2026-11-07T17:00:01Z"));
        LocalDate today = quota.charge(member);

        quota.refund(member, chargedOn);

        assertThat(ledger.count(chargedOn, "member:" + member)).isEqualTo(1);
        assertThat(ledger.count(chargedOn, "global")).isEqualTo(1);
        assertThat(ledger.count(today, "member:" + member)).isEqualTo(1);
    }

    @Test
    void aRefundNeverGoesBelowZero() {
        ChatQuota quota = quota(1000);
        UUID member = UUID.randomUUID();

        quota.refund(member, LocalDate.of(2026, 11, 7));

        assertThat(ledger.count(LocalDate.of(2026, 11, 7), "member:" + member)).isZero();
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final class MemoryLedger implements QuotaLedger {

        private final Map<String, Integer> counts = new HashMap<>();

        int count(LocalDate day, String key) {
            return counts.getOrDefault(day + "|" + key, 0);
        }

        @Override
        public OptionalInt charge(LocalDate day, List<Subject> subjects) {
            for (int i = 0; i < subjects.size(); i++) {
                if (count(day, subjects.get(i).key()) >= subjects.get(i).limit()) {
                    return OptionalInt.of(i);
                }
            }
            subjects.forEach(subject -> counts.merge(day + "|" + subject.key(), 1, Integer::sum));
            return OptionalInt.empty();
        }

        @Override
        public void refund(LocalDate day, List<String> keys) {
            keys.forEach(key -> counts.computeIfPresent(day + "|" + key, (k, v) -> Math.max(0, v - 1)));
        }
    }
}
