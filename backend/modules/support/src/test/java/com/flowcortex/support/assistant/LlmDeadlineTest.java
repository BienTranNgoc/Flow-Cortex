package com.flowcortex.support.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flowcortex.llm.api.LlmJsonResult;
import com.flowcortex.llm.api.LlmSlotRequest;
import com.flowcortex.llm.api.LlmSlots;
import com.flowcortex.llm.api.LlmTimeouts;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

class LlmDeadlineTest {

    private static final LlmSlotRequest REQUEST = new LlmSlotRequest("support-assistant", null, "câu hỏi", null, 0.2);

    private static LlmSlots slots(Answer<LlmJsonResult> answer) {
        LlmSlots slots = mock(LlmSlots.class);
        when(slots.generateJson(any(LlmSlotRequest.class))).thenAnswer(answer);
        return slots;
    }

    @Test
    void aCallStillRunningAtTheDeadlineIsATimeoutAndIsInterrupted() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        try (LlmDeadline deadline = new LlmDeadline(slots(call -> {
            try {
                Thread.sleep(Duration.ofSeconds(30));
            } catch (InterruptedException e) {
                interrupted.countDown();
            }
            return new LlmJsonResult.Answered("{}", "m", 1, 1);
        }), Duration.ofMillis(100))) {
            long started = System.nanoTime();

            LlmJsonResult result = deadline.call(REQUEST);

            assertThat(result).isEqualTo(new LlmJsonResult.Failed(LlmJsonResult.Kind.TIMEOUT));
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
            assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void theDeadlineIsTheConfiguredAnswerTimeout() {
        try (LlmDeadline deadline = new LlmDeadline(slots(call -> {
            try {
                Thread.sleep(Duration.ofSeconds(30));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new LlmJsonResult.Blocked("SAFETY");
        }), (LlmTimeouts) () -> Duration.ofMillis(100))) {
            assertThat(deadline.call(REQUEST)).isEqualTo(new LlmJsonResult.Failed(LlmJsonResult.Kind.TIMEOUT));
        }
    }

    @Test
    void aCallWithinTheDeadlinePassesItsResultThrough() {
        LlmJsonResult blocked = new LlmJsonResult.Blocked("SAFETY");
        try (LlmDeadline deadline = new LlmDeadline(slots(call -> blocked), Duration.ofSeconds(5))) {
            assertThat(deadline.call(REQUEST)).isSameAs(blocked);
        }
    }

    @Test
    void theCallersObservationIsCurrentInsideTheCall() {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(context -> true);
        AtomicReference<Observation> seen = new AtomicReference<>();
        Observation request = Observation.start("http.server.requests", registry);
        try (LlmDeadline deadline = new LlmDeadline(slots(call -> {
            seen.set(registry.getCurrentObservation());
            return new LlmJsonResult.Blocked("SAFETY");
        }), Duration.ofSeconds(5)); Observation.Scope scope = request.openScope()) {
            deadline.call(REQUEST);
        } finally {
            request.stop();
        }

        assertThat(seen.get()).isSameAs(request);
    }

    @Test
    void aClientThatThrowsIsAnUpstreamFailure() {
        try (LlmDeadline deadline = new LlmDeadline(slots(call -> {
            throw new IllegalStateException("boom");
        }), Duration.ofSeconds(5))) {
            assertThat(deadline.call(REQUEST)).isEqualTo(new LlmJsonResult.Failed(LlmJsonResult.Kind.UPSTREAM));
        }
    }
}
