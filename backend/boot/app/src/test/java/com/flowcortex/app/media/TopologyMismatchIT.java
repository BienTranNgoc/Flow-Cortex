package com.flowcortex.app.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The backend against a broker that already holds the media topology: declared with other
 * arguments it stays not ready, says why once and consumes nothing; declared exactly as the
 * worker's test fixture does, it is equivalent and the backend is ready.
 */
@ExtendWith(OutputCaptureExtension.class)
class TopologyMismatchIT extends AbstractIsolatedBrokerIT {

    @Test
    void aJobsQueueWithAnotherDeliveryLimitKeepsTheBackendNotReadyAndConsumingNothing(CapturedOutput output)
            throws Exception {
        String vhost = newVirtualHost();
        try (Connection connection = brokerConnection(vhost); Channel channel = connection.createChannel()) {
            channel.queueDeclare("media.jobs", true, false, false, Map.of(
                    "x-queue-type", "quorum",
                    "x-delivery-limit", 5,
                    "x-dead-letter-exchange", "media.dlx",
                    "x-consumer-timeout", 1_200_000));
        }
        int errorLinesBefore = errorLines(output).size();

        try (ConfigurableApplicationContext context = startApplication(vhost, builder -> { })) {
            assertThat(context.getBean(ApplicationAvailability.class).getReadinessState())
                    .isEqualTo(ReadinessState.REFUSING_TRAFFIC);
            assertThat(readyz(context)).isEqualTo(503);
            assertThat(context.getBean(RabbitListenerEndpointRegistry.class).getListenerContainers())
                    .isNotEmpty()
                    .noneMatch(container -> container.isRunning());
        }

        List<String> errors = errorLines(output).subList(errorLinesBefore, errorLines(output).size());
        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst()).contains("media.jobs", "x-delivery-limit", "delete", "re-create");
    }

    /** The argument dicts of {@code worker/tests/conftest.py::declare_topology}, key for key. */
    @Test
    void theTopologyAsTheWorkerDeclaresItIsEquivalentAndTheBackendIsReady(CapturedOutput output) throws Exception {
        String vhost = newVirtualHost();
        try (Connection connection = brokerConnection(vhost); Channel channel = connection.createChannel()) {
            channel.exchangeDeclare("media.unrouted", "fanout", true);
            channel.queueDeclare("media.unrouted", true, false, false, Map.of("x-queue-type", "quorum"));
            channel.queueBind("media.unrouted", "media.unrouted", "");
            channel.exchangeDeclare("media", "direct", true, false, Map.of("alternate-exchange", "media.unrouted"));
            channel.exchangeDeclare("media.dlx", "direct", true);
            Map<String, Object> deadLetter = Map.of("x-queue-type", "quorum", "x-dead-letter-exchange", "media.dlx");
            Map<String, Object> jobs = new HashMap<>(deadLetter);
            jobs.put("x-delivery-limit", 3);
            jobs.put("x-consumer-timeout", 1_200_000);
            Map<String, Map<String, Object>> queues = Map.of(
                    "job", jobs, "result", deadLetter, "progress", deadLetter);
            Map<String, String> names = Map.of("job", "media.jobs", "result", "media.results", "progress", "media.progress");
            for (Map.Entry<String, Map<String, Object>> queue : queues.entrySet()) {
                String name = names.get(queue.getKey());
                channel.queueDeclare(name, true, false, false, queue.getValue());
                channel.queueBind(name, "media", queue.getKey());
                channel.queueDeclare(name + ".dlq", true, false, false, Map.of("x-queue-type", "quorum"));
                channel.queueBind(name + ".dlq", "media.dlx", queue.getKey());
            }
        }
        int errorLinesBefore = errorLines(output).size();

        try (ConfigurableApplicationContext context = startApplication(vhost, builder -> { })) {
            assertThat(context.getBean(ApplicationAvailability.class).getReadinessState())
                    .isEqualTo(ReadinessState.ACCEPTING_TRAFFIC);
            assertThat(readyz(context)).isEqualTo(200);
            assertThat(context.getBean(RabbitListenerEndpointRegistry.class).getListenerContainers())
                    .isNotEmpty()
                    .allMatch(container -> container.isRunning());
        }
        assertThat(errorLines(output).subList(errorLinesBefore, errorLines(output).size())).isEmpty();
    }

    /** The status of an anonymous GET of the readiness probe on the API port. */
    private static int readyz(ConfigurableApplicationContext context) throws IOException, InterruptedException {
        String port = context.getEnvironment().getProperty("local.server.port");
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/readyz")).GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }

    private static List<String> errorLines(CapturedOutput output) {
        return output.getAll().lines().filter(line -> line.contains(" ERROR ")).toList();
    }
}
