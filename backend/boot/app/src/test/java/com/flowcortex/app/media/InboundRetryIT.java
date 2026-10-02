package com.flowcortex.app.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.flowcortex.media.application.MediaInbound;
import com.flowcortex.media.application.MediaProperties;
import com.flowcortex.user.api.UserService;
import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.WorkflowService;
import com.flowcortex.workflow.api.WorkflowView;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.GetResponse;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * How often the result listener hands one message to {@link MediaInbound}: a failure to record it
 * is retried, an invalid message is not. Counted on a spy that replaces the inbound bean, on a
 * broker no other test context consumes from.
 */
@ExtendWith(OutputCaptureExtension.class)
class InboundRetryIT extends AbstractIsolatedBrokerIT {

    private static String vhost;
    private static ConfigurableApplicationContext context;
    private static MediaInbound inbound;

    @BeforeAll
    static void start() throws Exception {
        vhost = newVirtualHost();
        context = startApplication(vhost, builder -> builder.initializers(applicationContext ->
                ((GenericApplicationContext) applicationContext).registerBean("countingInbound", MediaInbound.class,
                        () -> spy(new MediaInbound(applicationContext.getBean(Validator.class), applicationContext,
                                applicationContext.getBean(MediaProperties.class))),
                        definition -> definition.setPrimary(true))));
        inbound = context.getBean(MediaInbound.class);
        assertThat(mockingDetails(inbound).isSpy()).isTrue();
    }

    @AfterAll
    static void stop() {
        context.close();
    }

    @Test
    void aResultThatCannotBeRecordedIsTriedThreeTimesThenDeadLetteredNamingTheJob(CapturedOutput output)
            throws Exception {
        reset(inbound);
        UUID[] step = runningProbeStep();
        doThrow(new DataAccessResourceFailureException("database unavailable")).when(inbound).acceptResult(any());

        publishResult(completed(step[0], step[1], ""));

        assertThat(deadLettered()).contains(step[0].toString());
        verify(inbound, times(3)).acceptResult(any());
        assertThat(context.getBean(JdbcTemplate.class).queryForObject(
                "SELECT status FROM run_step WHERE id = ?", String.class, step[0])).isEqualTo("running");
        assertThat(output.getAll().lines()
                .filter(line -> line.contains(" WARN ") && line.contains(step[0].toString())))
                .anyMatch(line -> line.contains("could not be recorded"));
    }

    @Test
    void anInvalidResultIsHandedOverOnceAndDeadLetteredWithoutRetries() throws Exception {
        reset(inbound);
        UUID jobId = UUID.randomUUID();
        String withErrorBlock = completed(jobId, UUID.randomUUID(),
                ",\"error\":{\"code\":\"probe_failed\",\"message\":\"x\",\"retryable\":false}");

        publishResult(withErrorBlock);

        assertThat(deadLettered()).contains(jobId.toString());
        verify(inbound, times(1)).acceptResult(any());
    }

    /** {@code [stepId, runId]} of a running probe step seeded straight into the database. */
    private static UUID[] runningProbeStep() {
        UserView owner = context.getBean(UserService.class)
                .createInitialAdmin("inbound-retry-" + UUID.randomUUID() + "@example.com", "password123", "Retry");
        WorkflowView workflow = context.getBean(WorkflowService.class).createWorkflow(owner.id(),
                "retry-" + UUID.randomUUID(), false, List.of(new NewWorkflowStep("Inspect", "probe", null)));
        UUID runId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
        jdbc.update("INSERT INTO workflow_run (id, workflow_id, owner_id, status, input, started_at) "
                + "VALUES (?, ?, ?, 'running', 'clip.mp4', ?)", runId, workflow.id(), owner.id(), now);
        jdbc.update("INSERT INTO run_step (id, run_id, step_order, type, status, started_at, queue_deadline) "
                + "VALUES (?, ?, 1, 'probe', 'running', ?, ?)", stepId, runId, now,
                Timestamp.from(Instant.now().plusSeconds(1800)));
        return new UUID[] {stepId, runId};
    }

    private static String completed(UUID jobId, UUID runId, String extra) {
        return "{\"schemaVersion\":\"1\",\"jobId\":\"" + jobId + "\",\"runId\":\"" + runId + "\",\"attempt\":1,"
                + "\"deliveryCount\":0,\"status\":\"completed\",\"data\":{\"durationMs\":1000},"
                + "\"finishedAt\":\"" + Instant.now() + "\",\"workerId\":\"test-worker\"" + extra + "}";
    }

    private static void publishResult(String body) throws Exception {
        try (Connection connection = brokerConnection(vhost); Channel channel = connection.createChannel()) {
            channel.basicPublish("media", "result", new AMQP.BasicProperties.Builder()
                    .contentType("application/json").deliveryMode(2).build(), body.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String deadLettered() throws Exception {
        try (Connection connection = brokerConnection(vhost); Channel channel = connection.createChannel()) {
            GetResponse dead = await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                    .until(() -> channel.basicGet("media.results.dlq", true), response -> response != null);
            return new String(dead.getBody(), StandardCharsets.UTF_8);
        }
    }
}
