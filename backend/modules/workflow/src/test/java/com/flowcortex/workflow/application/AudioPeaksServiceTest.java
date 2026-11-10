package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.workflow.api.PeaksView;
import com.flowcortex.workflow.domain.RunStepAudioPeaks;
import com.flowcortex.workflow.infrastructure.RunStepAudioPeaksRepository;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.server.ResponseStatusException;

class AudioPeaksServiceTest {

    private static final UUID STEP = UUID.randomUUID();

    private final Map<RunStepAudioPeaks.Key, RunStepAudioPeaks> rows = new ConcurrentHashMap<>();
    private final AtomicInteger decodes = new AtomicInteger();
    private final ExecutorService pool = Executors.newCachedThreadPool();

    private PeaksOutcome next = new PeaksOutcome.Peaks(new float[] {0.5f, 1.0f});
    private CountDownLatch release = new CountDownLatch(0);
    private CountDownLatch started = new CountDownLatch(0);

    @AfterEach
    void stopThreads() {
        release.countDown();
        pool.shutdownNow();
    }

    /** An in-memory table honouring first-writer-wins, behind the repository interface. */
    private RunStepAudioPeaksRepository repository() {
        return (RunStepAudioPeaksRepository) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {RunStepAudioPeaksRepository.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "findById" -> Optional.ofNullable(rows.get((RunStepAudioPeaks.Key) args[0]));
                    case "insertIfAbsent" -> {
                        RunStepAudioPeaks.Key key = new RunStepAudioPeaks.Key((UUID) args[0], (Short) args[1],
                                (String) args[2]);
                        RunStepAudioPeaks row = new RunStepAudioPeaks((UUID) args[0], (Short) args[1],
                                (String) args[2], (String) args[3], (Instant) args[4]);
                        yield rows.putIfAbsent(key, row) == null ? 1 : 0;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private AudioPeaksService service(Duration wait) {
        return new AudioPeaksService(repository(), (bucket, key) -> {
            decodes.incrementAndGet();
            started.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return next;
        }, wait);
    }

    private PeaksView peaks(AudioPeaksService service, short attempt, String name) {
        return service.peaks(STEP, attempt, name, "bucket", "runs/r/steps/2/" + name);
    }

    @Test
    void aStoredResultIsServedWithoutDecoding() {
        AudioPeaksService service = service(Duration.ofMillis(50));

        PeaksView first = peaks(service, (short) 1, "a.wav");
        PeaksView second = peaks(service, (short) 1, "a.wav");

        assertThat(first.peaks()).containsExactly(0.5, 1.0);
        assertThat(second).isEqualTo(first);
        assertThat(decodes).hasValue(1);
        assertThat(rows).hasSize(1);
    }

    @Test
    void aNullResultIsStoredAndServed() {
        next = new PeaksOutcome.NotDecodable();
        AudioPeaksService service = service(Duration.ofMillis(50));

        assertThat(peaks(service, (short) 1, "a.wav").peaks()).isNull();
        assertThat(peaks(service, (short) 1, "a.wav").peaks()).isNull();

        assertThat(decodes).hasValue(1);
        assertThat(rows).hasSize(1);
        assertThat(rows.values().iterator().next().getPeaks()).isNull();
    }

    @Test
    void aNewAttemptComputesItsOwn() {
        AudioPeaksService service = service(Duration.ofMillis(50));

        peaks(service, (short) 1, "a.wav");
        next = new PeaksOutcome.Peaks(new float[] {1.0f, 0.25f});
        PeaksView second = peaks(service, (short) 2, "a.wav");

        assertThat(second.peaks()).containsExactly(1.0, 0.25);
        assertThat(decodes).hasValue(2);
        assertThat(rows).hasSize(2);
    }

    @Test
    void aThirdDecodeWaitingTenSecondsIsBusyAndStoresNothing() throws Exception {
        release = new CountDownLatch(1);
        started = new CountDownLatch(2);
        AudioPeaksService service = service(Duration.ofMillis(50));
        Future<PeaksView> one = pool.submit(() -> peaks(service, (short) 1, "a.wav"));
        Future<PeaksView> two = pool.submit(() -> peaks(service, (short) 1, "b.wav"));
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> peaks(service, (short) 1, "c.wav"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode().value()).isEqualTo(503);
                    assertThat(e.getDetailMessageCode()).isEqualTo("workflow.peaks.busy");
                    assertThat(e.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("10");
                });
        assertThat(rows).isEmpty();

        release.countDown();
        one.get(10, TimeUnit.SECONDS);
        two.get(10, TimeUnit.SECONDS);
        assertThat(peaks(service, (short) 1, "c.wav").peaks()).containsExactly(0.5, 1.0);
        assertThat(rows).hasSize(3);
    }

    @Test
    void aStoreFailureIsUnavailableAndStoresNothing() {
        next = new PeaksOutcome.StoreUnavailable();
        AudioPeaksService service = service(Duration.ofMillis(50));

        assertThatThrownBy(() -> peaks(service, (short) 1, "a.wav"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode().value()).isEqualTo(503);
                    assertThat(e.getDetailMessageCode()).isEqualTo("workflow.peaks.unavailable");
                    assertThat(e.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("10");
                });
        assertThat(rows).isEmpty();

        next = new PeaksOutcome.Peaks(new float[] {1.0f});
        assertThat(peaks(service, (short) 1, "a.wav").peaks()).containsExactly(1.0);
        assertThat(decodes).hasValue(2);
    }

    @Test
    void aMidBodyReadFailureStoresNothing() {
        AudioPeaksService service = new AudioPeaksService(repository(), (bucket, key) -> {
            decodes.incrementAndGet();
            return decodes.get() == 1 ? new PeaksOutcome.StoreUnavailable() : next;
        }, Duration.ofMillis(50));

        assertThatThrownBy(() -> peaks(service, (short) 1, "a.wav")).isInstanceOf(ResponseStatusException.class);
        assertThat(rows).isEmpty();
        assertThat(peaks(service, (short) 1, "a.wav").peaks()).containsExactly(0.5, 1.0);
        assertThat(decodes).hasValue(2);
    }
}
