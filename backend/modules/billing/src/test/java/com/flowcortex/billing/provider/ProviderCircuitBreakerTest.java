package com.flowcortex.billing.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.billing.provider.ProviderCircuitBreaker.CircuitListener;
import com.flowcortex.billing.provider.ProviderCircuitBreaker.CircuitPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProviderCircuitBreakerTest {

    private static final Instant START = Instant.parse("2026-11-10T00:00:00Z");

    private final MutableClock clock = new MutableClock(START);
    private final List<String> events = new ArrayList<>();
    private final CircuitListener listener = new CircuitListener() {
        @Override
        public void refused(String provider, String endpoint, CircuitState state) {
            events.add("refused:" + provider + ":" + endpoint + ":" + state.value());
        }

        @Override
        public void transitioned(String provider, CircuitState from, CircuitState to) {
            events.add(provider + ":" + from.value() + "->" + to.value());
        }
    };
    private final ProviderCircuitBreaker breaker = new ProviderCircuitBreaker(
            new CircuitPolicy(true, 5, Duration.ofSeconds(30)), clock, listener);

    @Test
    void staysClosedAfterFourFailuresAndOpensOnTheFifth() {
        failTimes(4);
        assertThat(events).isEmpty();

        failTimes(1);

        assertThat(events).containsExactly("momo:closed->open");
    }

    @Test
    void refusesTheSixthCallWithTheRetryTimeAndNeverInvokesTheSupplier() {
        failTimes(5);
        AtomicInteger invoked = new AtomicInteger();
        clock.advance(Duration.ofSeconds(10));

        assertThatThrownBy(() -> breaker.run("momo", "create", invoked::incrementAndGet))
                .isInstanceOfSatisfying(ProviderCircuitOpenException.class, e -> {
                    assertThat(e.retryAt()).isEqualTo(START.plusSeconds(30));
                    assertThat(e.provider()).isEqualTo("momo");
                    assertThat(e.endpoint()).isEqualTo("create");
                    assertThat(e.state()).isEqualTo(CircuitState.OPEN);
                    assertThat(e.getMessage()).contains("nothing was sent");
                });
        assertThat(invoked).hasValue(0);
        assertThat(events).containsExactly("momo:closed->open", "refused:momo:create:open");
    }

    @Test
    void aUsableAnswerBetweenFailuresResetsTheCount() {
        failTimes(4);
        assertThat(call()).isEqualTo("ok");
        failTimes(4);

        assertThat(call()).isEqualTo("ok");
        assertThat(events).isEmpty();
    }

    @Test
    void anAnswerThatIsNotATransportFailureIsUsableWhateverItSays() {
        failTimes(4);
        assertThat(breaker.run("momo", "create", () -> "resultCode=1006")).isEqualTo("resultCode=1006");
        failTimes(4);

        assertThat(events).isEmpty();
    }

    @Test
    void letsOneProbeThroughAfterTheOpenPeriodAndRefusesAConcurrentSecondCall() throws Exception {
        failTimes(5);
        clock.advance(Duration.ofSeconds(30));
        CountDownLatch probeStarted = new CountDownLatch(1);
        CountDownLatch releaseProbe = new CountDownLatch(1);
        AtomicInteger invoked = new AtomicInteger();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<String> probe = pool.submit(() -> breaker.run("momo", "create", () -> {
                invoked.incrementAndGet();
                probeStarted.countDown();
                await(releaseProbe);
                return "ok";
            }));
            assertThat(probeStarted.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> breaker.run("momo", "create", invoked::incrementAndGet))
                    .isInstanceOfSatisfying(ProviderCircuitOpenException.class,
                            e -> assertThat(e.state()).isEqualTo(CircuitState.HALF_OPEN));
            releaseProbe.countDown();

            assertThat(probe.get(5, TimeUnit.SECONDS)).isEqualTo("ok");
        } finally {
            pool.shutdownNow();
        }
        assertThat(invoked).hasValue(1);
        assertThat(events).containsExactly("momo:closed->open", "momo:open->half_open",
                "refused:momo:create:half_open", "momo:half_open->closed");
        assertThat(call()).isEqualTo("ok");
    }

    @Test
    void aFailedProbeReopensTheCircuitForAnotherOpenPeriod() {
        failTimes(5);
        clock.advance(Duration.ofSeconds(30));

        failTimes(1);

        assertThat(events).containsExactly("momo:closed->open", "momo:open->half_open", "momo:half_open->open");
        clock.advance(Duration.ofSeconds(29));
        assertThatThrownBy(this::call).isInstanceOfSatisfying(ProviderCircuitOpenException.class,
                e -> assertThat(e.retryAt()).isEqualTo(START.plusSeconds(60)));
        clock.advance(Duration.ofSeconds(1));
        assertThat(call()).isEqualTo("ok");
    }

    @Test
    void aProbeThatDiesOfAnotherReasonFreesTheProbeSlot() {
        failTimes(5);
        clock.advance(Duration.ofSeconds(30));

        assertThatThrownBy(() -> breaker.run("momo", "create", () -> {
            throw new IllegalStateException("local fault");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(call()).isEqualTo("ok");
    }

    @Test
    void aSwitchedOffBreakerInvokesEveryCall() {
        ProviderCircuitBreaker off = new ProviderCircuitBreaker(
                new CircuitPolicy(false, 5, Duration.ofSeconds(30)), clock, listener);
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> off.run("momo", "create", () -> {
                throw transport();
            })).isInstanceOf(ProviderTransportException.class);
        }

        assertThat(off.run("momo", "create", () -> "ok")).isEqualTo("ok");
        assertThat(events).isEmpty();
    }

    @Test
    void rejectsAPolicyThatCannotWork() {
        assertThatThrownBy(() -> new CircuitPolicy(true, 0, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CircuitPolicy(true, 5, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private String call() {
        return breaker.run("momo", "create", () -> "ok");
    }

    private void failTimes(int times) {
        for (int i = 0; i < times; i++) {
            assertThatThrownBy(() -> breaker.run("momo", "create", () -> {
                throw transport();
            })).isInstanceOf(ProviderTransportException.class);
        }
    }

    private static ProviderTransportException transport() {
        return new ProviderTransportException(ProviderTransportException.Kind.TIMEOUT, "momo", "create", null);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
