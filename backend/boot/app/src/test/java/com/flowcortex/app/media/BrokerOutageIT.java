package com.flowcortex.app.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.flowcortex.media.api.MediaCapability;
import com.flowcortex.media.api.MediaJob;
import com.flowcortex.media.api.MediaJobInput;
import com.flowcortex.media.api.MediaJobs;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.GetResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A broker that stops answering: at startup the backend waits for it instead of giving up, and a
 * job it could not hand over is resubmitted once the broker answers again.
 */
@ExtendWith(OutputCaptureExtension.class)
class BrokerOutageIT extends AbstractIsolatedBrokerIT {

    private static final String SHORT_CONNECT_TIMEOUT = "--spring.rabbitmq.connection-timeout=2s";

    @Test
    void aBrokerThatDoesNotAnswerAtStartupKeepsTheBackendNotReadyUntilItAnswers(CapturedOutput output)
            throws Exception {
        String vhost = newVirtualHost();
        pauseBroker();
        ConfigurableApplicationContext context = null;
        try {
            context = startApplication(vhost, builder -> { }, SHORT_CONNECT_TIMEOUT);
            ApplicationAvailability availability = context.getBean(ApplicationAvailability.class);
            RabbitListenerEndpointRegistry listeners = context.getBean(RabbitListenerEndpointRegistry.class);

            assertThat(availability.getReadinessState()).isEqualTo(ReadinessState.REFUSING_TRAFFIC);
            assertThat(listeners.getListenerContainers()).noneMatch(container -> container.isRunning());
            assertThat(output.getAll()).contains("WARN").contains("media broker")
                    .doesNotContain("down -v");

            unpauseBroker();
            await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> {
                assertThat(availability.getReadinessState()).isEqualTo(ReadinessState.ACCEPTING_TRAFFIC);
                assertThat(listeners.getListenerContainers()).isNotEmpty()
                        .allMatch(container -> container.isRunning());
            });
        } finally {
            unpauseBrokerIfPaused();
            if (context != null) {
                context.close();
            }
        }
    }

    @Test
    void aJobTheBrokerCouldNotTakeIsResubmittedOnceItAnswersAgain() throws Exception {
        String vhost = newVirtualHost();
        String database = newDatabase();
        try (ConfigurableApplicationContext context = startApplication(vhost, jdbcUrl(database), builder -> { },
                SHORT_CONNECT_TIMEOUT)) {
            assertThat(context.getBean(ApplicationAvailability.class).getReadinessState())
                    .isEqualTo(ReadinessState.ACCEPTING_TRAFFIC);
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            MediaJob job = job();

            context.getBean(CachingConnectionFactory.class).resetConnection();
            pauseBroker();
            try {
                context.getBean(TransactionTemplate.class)
                        .executeWithoutResult(status -> context.getBean(MediaJobs.class).enqueue(job));
                await().atMost(Duration.ofSeconds(60)).until(() -> jdbc.queryForObject(
                        "SELECT count(*) FROM event_publication WHERE status = 'FAILED' AND serialized_event LIKE ?",
                        Long.class, "%" + job.jobId() + "%") == 1);
            } finally {
                unpauseBroker();
            }

            try (Connection connection = brokerConnection(vhost); Channel channel = connection.createChannel()) {
                GetResponse delivered = await().atMost(Duration.ofSeconds(180)).pollInterval(Duration.ofSeconds(1))
                        .until(() -> channel.basicGet("media.jobs", true), response -> response != null);
                assertThat(delivered.getProps().getMessageId()).isEqualTo(job.jobId().toString());
                assertThat(delivered.getProps().getCorrelationId()).isEqualTo(job.runId().toString());
                assertThat(delivered.getProps().getHeaders().get("baggage")).asString()
                        .contains("runId=" + job.runId());
            }
        } finally {
            unpauseBrokerIfPaused();
        }
    }

    private static MediaJob job() {
        UUID runId = UUID.randomUUID();
        return new MediaJob("1", UUID.randomUUID(), runId, runId, 1, MediaCapability.PROBE,
                List.of(new MediaJobInput("media", UPLOAD_BUCKET, UUID.randomUUID() + "/" + UUID.randomUUID())),
                Map.of(), "runs/" + runId + "/steps/1/", 1, 300, 524_288_000L, Instant.now());
    }
}
