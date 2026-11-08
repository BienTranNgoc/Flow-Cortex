package com.flowcortex.realtime.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class RabbitRealtimeBusTest {

    private static final Duration WAIT = Duration.ofSeconds(5);

    private final RabbitOperations template = mock(RabbitOperations.class);
    private RabbitRealtimeBus bus;

    private RabbitRealtimeBus bus(int capacity) {
        bus = new RabbitRealtimeBus(template, capacity);
        return bus;
    }

    @AfterEach
    void close() {
        if (bus != null) {
            bus.close();
        }
    }

    @Test
    void publishSendsOneNonPersistentJsonMessageToTheFanoutExchange() {
        RabbitRealtimeBus bus = bus(10);

        bus.publish("run:a", "run.updated", Map.of("runId", "a"));

        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        await().atMost(WAIT).untilAsserted(() -> verify(template).send(eq("realtime.events"), eq(""), sent.capture()));
        Message message = sent.getValue();
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        assertThat(body).contains("\"topic\":\"run:a\"").contains("\"type\":\"run.updated\"")
                .contains("\"data\":{\"runId\":\"a\"}");
        assertThat(message.getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.NON_PERSISTENT);
        assertThat(message.getMessageProperties().getContentType()).isEqualTo("application/json");
    }

    @Test
    void publishReturnsAtOnceWhileTheTemplateBlocks() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            release.await(10, TimeUnit.SECONDS);
            return null;
        }).when(template).send(any(String.class), any(String.class), any(Message.class));
        RabbitRealtimeBus bus = bus(10);

        long started = System.nanoTime();
        bus.publish("run:a", "run.updated", Map.of());
        long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(elapsedMs).isLessThan(1000);
        release.countDown();
    }

    @Test
    void aThrowingTemplateNeverReachesTheCaller(CapturedOutput output) {
        doThrow(new IllegalStateException("broker down")).when(template)
                .send(any(String.class), any(String.class), any(Message.class));
        RabbitRealtimeBus bus = bus(10);

        assertThatCode(() -> bus.publish("run:failing", "run.updated", Map.of())).doesNotThrowAnyException();

        await().atMost(WAIT).untilAsserted(() -> assertThat(output.getAll()).contains("run:failing"));
    }

    @Test
    void aPayloadThatCannotBeSerializedNeverReachesTheCaller(CapturedOutput output) {
        RabbitRealtimeBus bus = bus(10);

        assertThatCode(() -> bus.publish("run:bad", "run.updated", new Object() {
            public Object getSelf() {
                throw new IllegalStateException("no");
            }
        })).doesNotThrowAnyException();

        assertThat(output.getAll()).contains("run:bad");
    }

    @Test
    void withThePublishQueueFullTheEventIsDroppedWithAWarnNamingTheTopic(CapturedOutput output)
            throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            release.await(10, TimeUnit.SECONDS);
            return null;
        }).when(template).send(any(String.class), any(String.class), any(Message.class));
        RabbitRealtimeBus bus = bus(1);
        bus.publish("run:running", "run.updated", Map.of());
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        bus.publish("run:queued", "run.updated", Map.of());

        long started = System.nanoTime();
        assertThatCode(() -> bus.publish("run:dropped", "run.updated", Map.of())).doesNotThrowAnyException();
        long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertThat(elapsedMs).isLessThan(1000);
        assertThat(output.getAll()).contains("WARN").contains("run:dropped");
        release.countDown();
    }
}
