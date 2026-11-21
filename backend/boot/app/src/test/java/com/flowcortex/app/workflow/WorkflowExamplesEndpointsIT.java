package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.RunView;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/** {@code /api/v1/workflow-examples} against the real, fully-wired app with credits on. */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "flowcortex.credits.enabled=true",
        "flowcortex.workflow.reaper-interval-ms=3600000"})
@AutoConfigureTestRestTemplate
class WorkflowExamplesEndpointsIT extends AbstractWorkflowHttpIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String LOAD_TEMPLATE_INPUT = "{\"template\":\"promo-brief\",\"productName\":\"Nodi Mug\"}";

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aVietnameseRequestGetsVietnameseTexts() throws Exception {
        UserView ana = newUser("ex-vi");

        JsonNode examples = JSON.readTree(list(loginAs(ana), "vi").getBody());

        JsonNode loadTemplate = byId(examples, "load-template");
        assertThat(loadTemplate.get("title").asText()).isEqualTo("Điền mẫu, duyệt, dùng một trường");
        assertThat(loadTemplate.get("description").asText()).startsWith("Bước đầu nạp mẫu");
    }

    @Test
    void anUnsupportedLanguageGetsEnglishTexts() throws Exception {
        UserView ana = newUser("ex-fr");

        JsonNode examples = JSON.readTree(list(loginAs(ana), "fr").getBody());

        assertThat(byId(examples, "load-template").get("title").asText())
                .isEqualTo("Fill a template, review, use a field");
    }

    @Test
    void theListCarriesTheShapeAndTheEstimateWithCreditsOn() throws Exception {
        UserView ana = newUser("ex-estimate");

        JsonNode examples = JSON.readTree(list(loginAs(ana), "en").getBody());

        assertThat(examples).hasSize(3);
        JsonNode humanInTheLoop = byId(examples, "human-in-the-loop");
        assertThat(humanInTheLoop.get("estimatedCredits").asLong()).isEqualTo(2);
        assertThat(humanInTheLoop.get("stepCount").asInt()).isEqualTo(3);
        assertThat(byId(examples, "load-template").get("estimatedCredits").asLong()).isEqualTo(1);
        assertThat(byId(examples, "load-template").get("sampleInput").asText()).isEqualTo(LOAD_TEMPLATE_INPUT);
        assertThat(byId(examples, "media-ingest").get("sampleInput").isNull()).isTrue();
    }

    @Test
    void anAnonymousListIsUnauthorized() {
        ResponseEntity<String> response = get("/api/v1/workflow-examples", null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void usingTheLoadTemplateExampleCreatesAPrivateDraftWithItsStoreEntry() throws Exception {
        UserView ana = newUser("ex-use");

        ResponseEntity<String> response = use("load-template", loginAs(ana), "en");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode workflow = JSON.readTree(response.getBody());
        assertThat(workflow.get("isShared").asBoolean()).isFalse();
        assertThat(workflow.get("status").asText()).isEqualTo("draft");
        assertThat(workflow.get("name").asText()).isEqualTo("Fill a template, review, use a field");
        assertThat(workflow.get("steps")).extracting(step -> step.get("type").asText())
                .containsExactly("load_template", "APPROVAL_CHECKPOINT", "echo");
        assertThat(jdbc.queryForList("SELECT key || ':' || data_type FROM workflow_data_store WHERE workflow_id = ?",
                String.class, UUID.fromString(workflow.get("id").asText()))).containsExactly("promo-brief:template");
    }

    @Test
    void theCreatedDraftRunsWithTheSampleInputAndPassesTheBriefOn() throws Exception {
        UserView ana = newUser("ex-run");
        awaitWallet(ana);
        jdbc.update("UPDATE credit_wallet SET balance = 10 WHERE user_id = ?", ana.id());
        String token = loginAs(ana);
        UUID workflowId = UUID.fromString(JSON.readTree(use("load-template", token, "en").getBody()).get("id")
                .asText());

        ResponseEntity<String> started = post("/api/v1/workflows/" + workflowId + "/runs", token,
                new StartRun(LOAD_TEMPLATE_INPUT), String.class);
        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID runId = UUID.fromString(JSON.readTree(started.getBody()).get("id").asText());
        pollUntil(ana.id(), runId, run -> "waiting_approval".equals(run.status()), TIMEOUT);
        JsonNode tasks = JSON.readTree(get("/api/v1/runs/" + runId + "/review-tasks", token, String.class).getBody());
        ResponseEntity<String> approved = post("/api/v1/review-tasks/" + tasks.get(0).get("id").asText() + "/approve",
                token, null, String.class);
        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);

        RunView finished = pollUntil(ana.id(), runId, run -> "completed".equals(run.status()), TIMEOUT);
        assertThat(finished.steps().get(2).output()).isEqualTo("Spring sale: 20% off every mug this week.");
    }

    @Test
    void anUnknownExampleIsNotFoundAndCreatesNothing() {
        UserView ana = newUser("ex-unknown");
        long before = workflowCount(ana);

        ResponseEntity<String> response = use("nope", loginAs(ana), "en");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(workflowCount(ana)).isEqualTo(before);
    }

    @Test
    void twoUsesGiveTwoDistinctDrafts() throws Exception {
        UserView ana = newUser("ex-twice");
        String token = loginAs(ana);

        UUID first = UUID.fromString(JSON.readTree(use("human-in-the-loop", token, "en").getBody()).get("id")
                .asText());
        UUID second = UUID.fromString(JSON.readTree(use("human-in-the-loop", token, "en").getBody()).get("id")
                .asText());

        assertThat(first).isNotEqualTo(second);
        assertThat(jdbc.queryForList("SELECT count(*) FROM workflow_step WHERE workflow_id IN (?, ?)", Long.class,
                first, second)).containsExactly(6L);
    }

    private ResponseEntity<String> list(String token, String language) {
        return restTemplate.exchange("/api/v1/workflow-examples", HttpMethod.GET, entity(token, language, null),
                String.class);
    }

    private ResponseEntity<String> use(String exampleId, String token, String language) {
        return restTemplate.exchange("/api/v1/workflow-examples/" + exampleId + "/workflows", HttpMethod.POST,
                entity(token, language, null), String.class);
    }

    private static HttpEntity<Object> entity(String token, String language, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setAcceptLanguageAsLocales(List.of(java.util.Locale.forLanguageTag(language)));
        headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON));
        return new HttpEntity<>(body, headers);
    }

    private static JsonNode byId(JsonNode examples, String id) {
        for (JsonNode example : examples) {
            if (id.equals(example.get("id").asText())) {
                return example;
            }
        }
        throw new AssertionError("No example " + id + " in " + examples);
    }

    private long workflowCount(UserView owner) {
        return jdbc.queryForObject("SELECT count(*) FROM workflow WHERE owner_id = ?", Long.class, owner.id());
    }

    private void awaitWallet(UserView member) {
        await(() -> jdbc.queryForObject("SELECT count(*) FROM credit_wallet WHERE user_id = ?", Long.class,
                member.id()) == 1);
    }

    private static void await(BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Condition not met within " + TIMEOUT);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting", e);
            }
        }
    }

    record StartRun(String input) {
    }
}
