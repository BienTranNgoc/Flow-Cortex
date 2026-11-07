package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.WorkflowView;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestClassOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.NestedTestConfiguration;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * {@code flowcortex.workflow.enabled=false}: new runs are refused cleanly (503, no row
 * written); already-finished runs stay readable. Dedicated {@code @SpringBootTest} context
 * since the property binds once at startup.
 *
 * <p>"Existing run stays readable" is seeded via SQL directly (not {@code startRun}, which this
 * context refuses) — standing in for a run completed before the flag was later flipped off.
 *
 * <p>The nested classes cover the dub switch, which counts only together with the provider key.
 * The context with dubbing on starts last: its reaper would otherwise move the run the switched-off
 * case seeds through a dub step that is available there.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "flowcortex.workflow.enabled=false")
@AutoConfigureTestRestTemplate
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
class WorkflowKillSwitchIT extends AbstractWorkflowHttpIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void startRunReturns503AndCreatesNoRunRow() {
        UserView user = newUser("killswitch-start");
        WorkflowView workflow = newWorkflow(user.id(), false, new NewWorkflowStep("only", "echo", null));
        String token = loginAs(user);

        Long before = countRunsFor(workflow.id());

        ResponseEntity<String> response =
                post("/api/v1/workflows/" + workflow.id() + "/runs", token, new StartRunRequestView("hello"),
                        String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.valueOf("application/problem+json"));
        assertThat(countRunsFor(workflow.id())).isEqualTo(before);
    }

    @Test
    void aRunThatFinishedBeforeTheFlagWasFlippedOffStaysReadable() {
        UserView user = newUser("killswitch-read");
        WorkflowView workflow = newWorkflow(user.id(), false, new NewWorkflowStep("only", "echo", null));
        String token = loginAs(user);
        UUID runId = seedCompletedRun(workflow.id(), user.id());

        ResponseEntity<RunResponseView> response = get("/api/v1/runs/" + runId, token, RunResponseView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo("completed");
    }

    private Long countRunsFor(UUID workflowId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM workflow_run WHERE workflow_id = ?", Long.class,
                workflowId);
    }

    private UUID seedCompletedRun(UUID workflowId, UUID ownerId) {
        UUID runId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update(
                "INSERT INTO workflow_run (id, workflow_id, owner_id, status, input, started_at, finished_at) "
                        + "VALUES (?, ?, ?, 'completed', 'seed-input', ?, ?)",
                runId, workflowId, ownerId, Timestamp.from(now), Timestamp.from(now));
        jdbcTemplate.update(
                "INSERT INTO run_step (id, run_id, step_order, type, status, input, output, started_at, finished_at) "
                        + "VALUES (?, ?, 1, 'echo', 'completed', 'seed-input', 'seed-input', ?, ?)",
                UUID.randomUUID(), runId, Timestamp.from(now), Timestamp.from(now));
        return runId;
    }

    private static final String TRANSCRIPT =
            "{\"segments\":[{\"index\":1,\"startMs\":500,\"endMs\":3100,\"zh\":\"你好朋友们\"}]}";

    /** Answers both provider endpoints the dub steps call, and counts what reaches it. */
    static final StubProvider PROVIDER = StubProvider.start();

    record WorkflowAvailabilityView(UUID id, boolean available) {
    }

    /**
     * The dub steps run against {@link #PROVIDER}, never the real provider. Each switch setting is
     * its own context, since the settings bind once at startup.
     */
    abstract static class DubContext extends AbstractWorkflowHttpIT {

        @Autowired
        JdbcTemplate jdbc;

        @DynamicPropertySource
        static void provider(DynamicPropertyRegistry registry) {
            registry.add("flowcortex.dub.gemini.base-url", PROVIDER::baseUrl);
        }

        WorkflowView dubWorkflow(UUID ownerId) {
            return newWorkflow(ownerId, false, new NewWorkflowStep("transcript", "echo", null),
                    new NewWorkflowStep("translate", "translate", null), new NewWorkflowStep("voice", "tts", null));
        }

        boolean available(WorkflowView workflow, String token) {
            return get("/api/v1/workflows/" + workflow.id(), token, WorkflowAvailabilityView.class).getBody()
                    .available();
        }

        void assertStartIsRefusedDubDisabled(WorkflowView workflow, String token) {
            ResponseEntity<String> response = post("/api/v1/workflows/" + workflow.id() + "/runs", token,
                    new StartRunRequestView(TRANSCRIPT), String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(response.getHeaders().getContentType())
                    .isEqualTo(MediaType.valueOf("application/problem+json"));
            assertThat(response.getBody()).contains("\"code\":\"dub_disabled\"");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_run WHERE workflow_id = ?", Long.class,
                    workflow.id())).isZero();
        }
    }

    @Nested
    @Order(1)
    @NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.OVERRIDE)
    @SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "flowcortex.dub.enabled=false",
        "flowcortex.dub.gemini.api-key=fake-key-3c9e",
        "flowcortex.workflow.reaper-interval-ms=300"})
    @AutoConfigureTestRestTemplate
    class DubSwitchedOff extends DubContext {

        @Test
        void theWorkflowReportsUnavailableAndAStartIsRefusedDubDisabled() {
            UserView user = newUser("dub-off");
            WorkflowView workflow = dubWorkflow(user.id());
            String token = loginAs(user);

            assertThat(available(workflow, token)).isFalse();
            assertStartIsRefusedDubDisabled(workflow, token);
        }

        @Test
        void aWorkflowWithoutDubStepsStaysAvailable() {
            UserView user = newUser("dub-off-echo");
            WorkflowView workflow = newWorkflow(user.id(), false, new NewWorkflowStep("only", "echo", null));

            assertThat(available(workflow, loginAs(user))).isTrue();
        }

        @Test
        void aRunThatReachesTranslateAfterTheSwitchWentOffFailsDubDisabledWithoutAProviderCall() {
            UserView user = newUser("dub-off-midrun");
            WorkflowView workflow = dubWorkflow(user.id());
            int requestsBefore = PROVIDER.requests();
            UUID runId = UUID.randomUUID();
            Instant now = Instant.now();
            // A run started while dubbing was on, its first step done before the switch went off.
            jdbc.update("INSERT INTO workflow_run (id, workflow_id, owner_id, status, input, started_at) "
                    + "VALUES (?, ?, ?, 'running', ?, ?)", runId, workflow.id(), user.id(), TRANSCRIPT,
                    Timestamp.from(now));
            jdbc.update("INSERT INTO run_step (id, run_id, step_order, type, status, input, output, started_at, "
                    + "finished_at) VALUES (?, ?, 1, 'echo', 'completed', ?, ?, ?, ?)", UUID.randomUUID(), runId,
                    TRANSCRIPT, TRANSCRIPT, Timestamp.from(now), Timestamp.from(now));
            jdbc.update("INSERT INTO run_step (id, run_id, step_order, type, status) VALUES (?, ?, 2, 'translate', "
                    + "'pending')", UUID.randomUUID(), runId);
            jdbc.update("INSERT INTO run_step (id, run_id, step_order, type, status) VALUES (?, ?, 3, 'tts', "
                    + "'pending')", UUID.randomUUID(), runId);

            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                    "SELECT error_code FROM run_step WHERE run_id = ? AND step_order = 2", String.class, runId))
                    .isEqualTo("dub_disabled"));

            assertThat(jdbc.queryForObject("SELECT status FROM workflow_run WHERE id = ?", String.class, runId))
                    .isEqualTo("error");
            assertThat(jdbc.queryForObject("SELECT status FROM run_step WHERE run_id = ? AND step_order = 3",
                    String.class, runId)).isEqualTo("pending");
            assertThat(PROVIDER.requests()).isEqualTo(requestsBefore);
        }
    }

    @Nested
    @Order(2)
    @NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.OVERRIDE)
    @SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "flowcortex.dub.enabled=true",
        "flowcortex.dub.gemini.api-key="})
    @AutoConfigureTestRestTemplate
    class DubKeyMissing extends DubContext {

        @Test
        void theWorkflowReportsUnavailableAndAStartIsRefusedDubDisabled() {
            UserView user = newUser("dub-no-key");
            WorkflowView workflow = dubWorkflow(user.id());
            String token = loginAs(user);

            assertThat(available(workflow, token)).isFalse();
            assertStartIsRefusedDubDisabled(workflow, token);
        }
    }

    @Nested
    @Order(3)
    @NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.OVERRIDE)
    @SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "flowcortex.dub.enabled=true",
        "flowcortex.dub.gemini.api-key=fake-key-3c9e"})
    @AutoConfigureTestRestTemplate
    class DubAvailable extends DubContext {

        @Test
        void theWorkflowReportsAvailableAndARunPassesTranslateAndVoiceAgainstTheStubbedProvider() {
            UserView user = newUser("dub-on");
            WorkflowView workflow = dubWorkflow(user.id());
            String token = loginAs(user);

            assertThat(available(workflow, token)).isTrue();

            ResponseEntity<RunResponseView> started = post("/api/v1/workflows/" + workflow.id() + "/runs", token,
                    new StartRunRequestView(TRANSCRIPT), RunResponseView.class);
            assertThat(started.getStatusCode()).isEqualTo(HttpStatus.OK);
            UUID runId = started.getBody().id();
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                    "SELECT status FROM workflow_run WHERE id = ?", String.class, runId)).isEqualTo("completed"));

            assertThat(jdbc.queryForObject("SELECT output FROM run_step WHERE run_id = ? AND step_order = 2",
                    String.class, runId)).contains("\"vi\":\"Xin chào các bạn\"").contains("\"zh\":\"你好朋友们\"");
            assertThat(jdbc.queryForObject("SELECT output FROM run_step WHERE run_id = ? AND step_order = 3",
                    String.class, runId)).isEqualTo("{\"voiced\":1}");
            assertThat(jdbc.queryForObject("SELECT outputs::text FROM run_step WHERE run_id = ? AND step_order = 3",
                    String.class, runId)).contains("\"name\": \"voice.wav\"")
                    .contains("\"name\": \"voice-manifest.json\"")
                    .contains("\"bucket\": \"" + UPLOAD_BUCKET + "\"");
            try (S3Client store = objectStore()) {
                String manifest = store.getObjectAsBytes(o -> o.bucket(UPLOAD_BUCKET)
                        .key("runs/" + runId + "/steps/3/voice-manifest.json")).asUtf8String();
                assertThat(manifest).contains("\"voice\":\"Kore\"").contains("\"vi\":\"Xin chào các bạn\"");
            }
            assertThat(PROVIDER.paths()).contains("/v1beta/models/gemini-3.5-flash-lite:generateContent",
                    "/v1beta/interactions");
        }
    }

    /** A local stand-in for the provider: a fixed translation and a tiny voice file. */
    static final class StubProvider {

        private final HttpServer server;
        private final List<String> paths = new CopyOnWriteArrayList<>();

        private StubProvider(HttpServer server) {
            this.server = server;
        }

        static StubProvider start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                StubProvider stub = new StubProvider(server);
                server.createContext("/", stub::answer);
                server.start();
                return stub;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private void answer(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            paths.add(path);
            exchange.getRequestBody().readAllBytes();
            String body = path.endsWith(":generateContent")
                    ? "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":"
                            + "\"[{\\\"index\\\":1,\\\"vi\\\":\\\"Xin chào các bạn\\\"}]\"}]}}]}"
                    : "{\"steps\":[{\"content\":[{\"data\":\""
                            + Base64.getEncoder().encodeToString("RIFF....WAVE".getBytes(StandardCharsets.US_ASCII))
                            + "\"}]}]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta";
        }

        int requests() {
            return paths.size();
        }

        List<String> paths() {
            return paths;
        }
    }
}
