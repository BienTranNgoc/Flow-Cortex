package com.flowcortex.user.phone;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Whether one more code may be sent to a number, from the times codes were already sent to it.
 * Precedence: the 24-hour cap, then the hourly cap, then the cooldown after the previous send
 * within the hour (60 s before the second, 120 s before the third, 300 s before every later one).
 */
final class OtpSendPolicy {

    static final int DAILY_CAP = 10;
    static final int HOURLY_CAP = 5;
    private static final Duration DAY = Duration.ofHours(24);
    private static final Duration HOUR = Duration.ofHours(1);

    enum Outcome {
        ALLOW,
        COOLDOWN,
        HOURLY_LIMIT,
        DAILY_LIMIT
    }

    /** {@code retryAfterSeconds} is 0 for {@link Outcome#ALLOW}, else whole seconds until the refusal lifts. */
    record Decision(Outcome outcome, long retryAfterSeconds) {

        static final Decision ALLOW = new Decision(Outcome.ALLOW, 0);
    }

    private OtpSendPolicy() {
    }

    static Decision decide(List<Instant> previousSends, Instant now) {
        List<Instant> inDay = within(previousSends, now.minus(DAY));
        if (inDay.size() >= DAILY_CAP) {
            Instant lifts = inDay.get(inDay.size() - DAILY_CAP).plus(DAY);
            return new Decision(Outcome.DAILY_LIMIT, secondsUntil(lifts, now));
        }
        List<Instant> inHour = within(inDay, now.minus(HOUR));
        if (inHour.size() >= HOURLY_CAP) {
            Instant lifts = inHour.get(inHour.size() - HOURLY_CAP).plus(HOUR);
            return new Decision(Outcome.HOURLY_LIMIT, secondsUntil(lifts, now));
        }
        if (!inHour.isEmpty()) {
            Instant allowedFrom = inHour.getLast().plus(cooldownAfter(inHour.size()));
            if (now.isBefore(allowedFrom)) {
                return new Decision(Outcome.COOLDOWN, secondsUntil(allowedFrom, now));
            }
        }
        return Decision.ALLOW;
    }

    private static Duration cooldownAfter(int sendsInHour) {
        return switch (sendsInHour) {
            case 1 -> Duration.ofSeconds(60);
            case 2 -> Duration.ofSeconds(120);
            default -> Duration.ofSeconds(300);
        };
    }

    /** Sends strictly after {@code windowStart}, oldest first. */
    private static List<Instant> within(List<Instant> sends, Instant windowStart) {
        return sends.stream().filter(sent -> sent.isAfter(windowStart)).sorted().toList();
    }

    private static long secondsUntil(Instant then, Instant now) {
        long millis = Duration.between(now, then).toMillis();
        return Math.max(1, (millis + 999) / 1000);
    }
}
