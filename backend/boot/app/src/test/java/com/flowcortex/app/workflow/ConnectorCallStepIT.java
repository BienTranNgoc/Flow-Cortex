package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowcortex.connector.api.ConnectorException;
import com.flowcortex.connector.api.HttpConnector;
import com.flowcortex.connector.api.HttpConnectorRequest;
import com.flowcortex.connector.api.HttpConnectorResponse;
import com.flowcortex.shared.web.GlobalExceptionHandler;
import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.RunStepView;
import com.flowcortex.workflow.api.RunView;
import com.flowcortex.workflow.api.WorkflowView;
import jakarta.validation.ConstraintViolationException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A workflow with a {@code connector.call} step through the real engine, the rate card and the start
 * guards, with the HTTP connector port replaced by a mock. {@code echo} is priced at 10 for the run
 * scenarios (put back after), so a workflow of echo then connector call is estimated at 10: the call
 * itself is free.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "flowcortex.credits.enabled=true",
        "flowcortex.workflow.reaper-interval-ms=3600000"})
@AutoConfigureTestRestTemplate
class ConnectorCallStepIT extends AbstractWorkflowHttpIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String CONFIG = "{\"connector\":\"notion-main\",\"url\":\"https://api.notion.com/v1/users\"}";

    /**
     * The connector controller wires the concrete service, so the port is replaced by a primary mock
     * next to it rather than by overriding the service bean.
     */
    @TestConfiguration
    static class MockConnectorConfig {

        @Bean
        @Primary
        HttpConnector mockHttpConnector() {
            return Mockito.mock(HttpConnector.class);
        }
    }

    @Autowired
    private HttpConnector httpConnector;

    @Autowired
    private GlobalExceptionHandler exceptionHandler;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void priceEchoForTheScenarios() {
        Mockito.reset(httpConnector);
        setEchoPrice(10);
    }

    @AfterEach
    void restoreTheSeededPrice() {
        setEchoPrice(1);
        Mockito.reset(httpConnector);
    }

    private void setEchoPrice(int credits) {
        int updated = jdbcTemplate.update("UPDATE rate_card SET credits_per_unit = ? WHERE rate_card_ref = 'echo' "
                + "AND effective_to IS NULL", credits);
        assertThat(updated).as("seeded open rate_card row of echo").isEqualTo(1);
    }

    record StartRequest(String input) {
    }

    record ProblemView(int status, String detail, String code, Map<String, String> errors) {
    }

    record PricedWorkflowView(UUID id, String name, Long estimatedCredits) {
    }

    record StartedRunView(UUID id, String status) {
    }

    @Test
    void theTypeIsRegisteredAndItsPricingRowIsFreeWithNoQuantityParameters() {
        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT estimator_kind, rate_card_ref, pricing_unit, "
                + "provider_key, cardinality(quantity_params) AS quantity_params FROM capability_pricing "
                + "WHERE capability = 'connector.call'");

        assertThat(row).containsEntry("estimator_kind", "free").containsEntry("rate_card_ref", null)
                .containsEntry("pricing_unit", null).containsEntry("provider_key", null)
                .containsEntry("quantity_params", 0);
        UserView owner = newUser("registered");
        assertThat(newWorkflow(owner.id(), false, new NewWorkflowStep("call", "connector.call", null, CONFIG)).steps())
                .extracting(step -> step.type()).containsExactly("connector.call");
    }

    @Test
    void aWorkflowIsEstimatedWithTheCallFreeRunsToCompletionAndChargesNoLedgerRowForTheCallStep() {
        UserView member = memberWithBalance("owner-run", 30);
        String token = loginAs(member);
        WorkflowView workflow = newWorkflow(member.id(), false, new NewWorkflowStep("probe", "echo", null),
                new NewWorkflowStep("call", "connector.call", null, CONFIG));
        HttpConnectorResponse answer = new HttpConnectorResponse(200, true, "application/json",
                "{\"results\":[]}", false);
        when(httpConnector.call(any(UUID.class), any(HttpConnectorRequest.class))).thenReturn(answer);

        assertThat(get("/api/v1/workflows/" + workflow.id(), token, PricedWorkflowView.class).getBody()
                .estimatedCredits()).isEqualTo(10);
        ResponseEntity<StartedRunView> started = post("/api/v1/workflows/" + workflow.id() + "/runs", token,
                new StartRequest("hello"), StartedRunView.class);

        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID runId = started.getBody().id();
        RunView finished = awaitTerminal(member.id(), runId);
        assertThat(finished.status()).isEqualTo("completed");
        RunStepView call = finished.steps().get(1);
        assertThat(call.status()).isEqualTo("completed");
        assertThat(call.output()).isEqualTo("{\"status\":200,\"ok\":true,\"contentType\":\"application/json\","
                + "\"body\":\"{\\\"results\\\":[]}\",\"truncated\":false}");
        ArgumentCaptor<HttpConnectorRequest> request = ArgumentCaptor.forClass(HttpConnectorRequest.class);
        verify(httpConnector).call(eq(member.id()), request.capture());
        assertThat(request.getValue().key()).isEqualTo("notion-main");
        assertThat(request.getValue().method()).isEqualTo("GET");
        assertThat(request.getValue().url()).isEqualTo("https://api.notion.com/v1/users");
        assertThat(request.getValue().body()).isNull();
        await(() -> balance(member) == 20);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM credit_ledger WHERE run_step_id = ?",
                Long.class, call.id())).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM credit_ledger WHERE run_id = ?", Long.class,
                runId)).isEqualTo(1);
    }

    @Test
    void aConnectorFailureEndsTheStepWithItsStableCode() {
        UserView member = memberWithBalance("owner-fail", 30);
        WorkflowView workflow = newWorkflow(member.id(), false,
                new NewWorkflowStep("call", "connector.call", null, CONFIG));
        when(httpConnector.call(any(UUID.class), any(HttpConnectorRequest.class)))
                .thenThrow(ConnectorException.of(ConnectorException.Reason.UPSTREAM_UNAVAILABLE));

        ResponseEntity<StartedRunView> started = post("/api/v1/workflows/" + workflow.id() + "/runs",
                loginAs(member), new StartRequest("hello"), StartedRunView.class);

        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.OK);
        RunView finished = awaitTerminal(member.id(), started.getBody().id());
        assertThat(finished.status()).isEqualTo("error");
        RunStepView call = finished.steps().get(0);
        assertThat(call.status()).isEqualTo("error");
        assertThat(call.errorCode()).isEqualTo("connector_upstream_unavailable");
        assertThat(call.errorMessage()).isNotBlank().doesNotContain("https://");
    }

    @Test
    void aStepHoldingACredentialHeaderIsRefusedAt400UnderItsConfig() {
        UserView member = newUser("credential-header");
        String config = "{\"connector\":\"notion-main\",\"url\":\"https://api.notion.com/v1/users\","
                + "\"headers\":{\"Authorization\":\"Bearer secret-value\"}}";

        assertThatThrownBy(() -> newWorkflow(member.id(), false,
                new NewWorkflowStep("call", "connector.call", null, config)))
                .isInstanceOfSatisfying(ConstraintViolationException.class, violation -> {
                    ProblemDetail problem = exceptionHandler.handleConstraintViolation(violation);
                    assertThat(problem.getStatus()).isEqualTo(400);
                    assertThat(problem.getProperties()).extractingByKey("errors")
                            .asInstanceOf(InstanceOfAssertFactories.MAP).containsOnlyKeys("steps[0].config");
                    assertThat(String.valueOf(problem.getProperties().get("errors"))).contains("Authorization")
                            .doesNotContain("secret-value");
                });
        assertThat(workflowService.listVisibleWorkflows(member.id())).extracting(WorkflowView::ownerId)
                .doesNotContain(member.id());
    }

    @Test
    void addingAStepHoldingACredentialHeaderOverHttpAnswers400UnderItsConfigAndAConnectorCallStepIsAccepted() {
        UserView member = newUser("credential-header-http");
        String token = loginAs(member);
        WorkflowView workflow = newWorkflow(member.id(), false);
        String steps = "/api/v1/workflows/" + workflow.id() + "/steps";
        String refused = "{\"type\":\"connector.call\",\"name\":\"call\",\"config\":{\"connector\":\"notion-main\","
                + "\"url\":\"https://api.notion.com/v1/users\",\"headers\":{\"Authorization\":\"Bearer secret-value\"}}}";
        String accepted = "{\"type\":\"connector.call\",\"name\":\"call\",\"config\":" + CONFIG + "}";

        ResponseEntity<ProblemView> response = postJson(steps, token, refused, ProblemView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().errors()).containsOnlyKeys("steps[0].config");
        assertThat(response.getBody().errors().get("steps[0].config")).contains("Authorization")
                .doesNotContain("secret-value");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM workflow_step WHERE workflow_id = ?",
                Long.class, workflow.id())).isZero();

        ResponseEntity<String> created = postJson(steps, token, accepted, String.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).contains("\"connector\":\"notion-main\"");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM workflow_step WHERE workflow_id = ? "
                + "AND type = 'connector.call'", Long.class, workflow.id())).isEqualTo(1);
    }

    @Test
    void anotherMemberCannotStartASharedWorkflowHoldingTheStepAndNoRunOrConnectorReadHappens() {
        UserView author = newUser("author");
        UserView starter = memberWithBalance("starter", 30);
        WorkflowView workflow = newWorkflow(author.id(), true, new NewWorkflowStep("probe", "echo", null),
                new NewWorkflowStep("call", "connector.call", null, CONFIG));

        ResponseEntity<ProblemView> response = post("/api/v1/workflows/" + workflow.id() + "/runs",
                loginAs(starter), new StartRequest("hello"), ProblemView.class);

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody().code()).isEqualTo("CONNECTOR_STEP_NOT_OWNER");
        assertThat(response.getBody().errors()).containsOnlyKeys("step2");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM workflow_run WHERE workflow_id = ?",
                Long.class, workflow.id())).isZero();
        assertThat(balance(starter)).isEqualTo(30);
        verifyNoInteractions(httpConnector);
    }

    private <T> ResponseEntity<T> postJson(String path, String token, String json, Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(json, headers), responseType);
    }

    private UserView memberWithBalance(String prefix, long balance) {
        UserView member = newUser(prefix);
        await(() -> jdbcTemplate.queryForObject("SELECT count(*) FROM credit_wallet WHERE user_id = ?", Long.class,
                member.id()) == 1);
        jdbcTemplate.update("UPDATE credit_wallet SET balance = ? WHERE user_id = ?", balance, member.id());
        return member;
    }

    private long balance(UserView member) {
        return jdbcTemplate.queryForObject("SELECT balance FROM credit_wallet WHERE user_id = ?", Long.class,
                member.id());
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
}
