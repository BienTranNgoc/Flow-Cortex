package com.flowcortex.user.phone;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.phone.OtpSendPolicy.Decision;
import com.flowcortex.user.phone.OtpSendPolicy.Outcome;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Previous send times (seconds after T0) and the request time → the decision. */
class OtpSendPolicyTest {

    private static final Instant T0 = Instant.parse("2026-10-16T08:00:00Z");
    private static final long MINUTE = 60;
    private static final long HOUR = 3600;

    static Stream<Arguments> fixtures() {
        return Stream.of(
                row("first send", sends(), 0, Outcome.ALLOW, 0),
                row("second send one second early", sends(0), 59, Outcome.COOLDOWN, 1),
                row("second send at 60 s", sends(0), 60, Outcome.ALLOW, 0),
                row("third send one second early", sends(0, 60), 179, Outcome.COOLDOWN, 1),
                row("third send 120 s after the second", sends(0, 60), 180, Outcome.ALLOW, 0),
                row("fourth send one second early", sends(0, 60, 180), 479, Outcome.COOLDOWN, 1),
                row("fourth send 300 s after the third", sends(0, 60, 180), 480, Outcome.ALLOW, 0),
                row("fifth send waits 300 s too", sends(0, 60, 180, 480), 600, Outcome.COOLDOWN, 180),
                row("fifth send 300 s after the fourth", sends(0, 60, 180, 480), 780, Outcome.ALLOW, 0),
                row("a send older than an hour does not count toward the ladder", sends(0), HOUR + 10,
                        Outcome.ALLOW, 0),
                row("five sends in the last 50 minutes", sends(0, 60, 180, 480, 780), 50 * MINUTE,
                        Outcome.HOURLY_LIMIT, HOUR - 50 * MINUTE),
                row("the hourly cap lifts when the oldest send leaves the hour", sends(0, 60, 180, 480, 780), HOUR,
                        Outcome.ALLOW, 0),
                row("ten sends over the last 20 hours", tenSendsOverTwentyHours(), 20 * HOUR,
                        Outcome.DAILY_LIMIT, 4 * HOUR),
                row("the daily cap lifts when the oldest send leaves the day", tenSendsOverTwentyHours(), 24 * HOUR,
                        Outcome.ALLOW, 0),
                row("daily wins over hourly", dailyAndHourlyBothReached(), 24 * HOUR - 1, Outcome.DAILY_LIMIT, 1));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void decides(String name, List<Instant> previousSends, Instant now, Outcome outcome, long retryAfterSeconds) {
        Decision decision = OtpSendPolicy.decide(previousSends, now);

        assertThat(decision.outcome()).isEqualTo(outcome);
        assertThat(decision.retryAfterSeconds()).isEqualTo(retryAfterSeconds);
    }

    private static Arguments row(String name, List<Instant> previousSends, long nowSeconds, Outcome outcome,
            long retryAfterSeconds) {
        return Arguments.of(name, previousSends, T0.plusSeconds(nowSeconds), outcome, retryAfterSeconds);
    }

    private static List<Instant> sends(long... secondsAfterT0) {
        return Arrays.stream(secondsAfterT0).mapToObj(T0::plusSeconds).toList();
    }

    /** One send every 2 hours from T0 to T0 + 18 h — none within the hour before T0 + 20 h. */
    private static List<Instant> tenSendsOverTwentyHours() {
        return IntStream.range(0, 10).mapToObj(i -> T0.plusSeconds(i * 2 * HOUR)).toList();
    }

    /** Five sends early in the day and five in the last hour before T0 + 24 h: both caps are reached. */
    private static List<Instant> dailyAndHourlyBothReached() {
        long lastHour = 23 * HOUR;
        return sends(0, 60, 180, 480, 780,
                lastHour, lastHour + 60, lastHour + 180, lastHour + 480, lastHour + 780);
    }
}
