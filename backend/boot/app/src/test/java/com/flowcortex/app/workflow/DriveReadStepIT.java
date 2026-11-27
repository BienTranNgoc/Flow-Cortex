package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.connector.api.DriveFile;
import com.flowcortex.connector.api.DriveFileText;
import com.flowcortex.connector.api.GoogleDrive;
import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.RunStepView;
import com.flowcortex.workflow.api.RunView;
import com.flowcortex.workflow.api.WorkflowView;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * A workflow with a {@code drive.read} step through the real engine, the rate card and the start guards, with the
 * Drive port replaced by a mock. {@code echo} is priced at 10 for the run scenarios (put back after), so a workflow
 * of the Drive step then echo is estimated at 10: the Drive step itself is free.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "flowcortex.credits.enabled=true",
        "flowcortex.workflow.reaper-interval-ms=3600000"})
@AutoConfigureTestRestTemplate
class DriveReadStepIT extends AbstractWorkflowHttpIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String FOLDER = "1AbCdEfGhIjK";
    private static final String CONFIG = "{\"folderId\":\"" + FOLDER + "\"}";

    @MockitoBean
    private GoogleDrive drive;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void switchOnAndPriceEchoForTheScenarios() {
        when(drive.enabled()).thenReturn(true);
        setEchoPrice(10);
    }

    @AfterEach
    void restoreTheSeededPrice() {
        setEchoPrice(1);
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
    void thePricingRowIsFreeWithNoQuantityParametersAndTheTypeIsRegistered() {
        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT estimator_kind, rate_card_ref, pricing_unit, "
                + "provider_key, cardinality(quantity_params) AS quantity_params FROM capability_pricing "
                + "WHERE capability = 'drive.read'");

        assertThat(row).containsEntry("estimator_kind", "free").containsEntry("rate_card_ref", null)
                .containsEntry("pricing_unit", null).containsEntry("provider_key", null)
                .containsEntry("quantity_params", 0);
        UserView owner = newUser("registered");
        assertThat(newWorkflow(owner.id(), false, new NewWorkflowStep("read", "drive.read", null, CONFIG)).steps())
                .extracting(step -> step.type()).containsExactly("drive.read");
    }

    @Test
    void aWorkflowIsEstimatedWithTheReadFreeRunsToCompletionAndChargesNoLedgerRowForTheReadStep() {
        UserView member = memberWithBalance("owner-run", 30);
        String token = loginAs(member);
        WorkflowView workflow = newWorkflow(member.id(), false, new NewWorkflowStep("read", "drive.read", null, CONFIG),
                new NewWorkflowStep("probe", "echo", null));
        DriveFile cv = new DriveFile("f1", "cv.pdf", "application/pdf", null, 1024L);
        when(drive.listFolder(eq(member.id()), eq(FOLDER), eq(20))).thenReturn(List.of(cv));
        when(drive.readText(eq(member.id()), eq(cv)))
                .thenReturn(new DriveFileText("cv text", "pdf", false, false, null));

        assertThat(get("/api/v1/workflows/" + workflow.id(), token, PricedWorkflowView.class).getBody()
                .estimatedCredits()).isEqualTo(10);
        ResponseEntity<StartedRunView> started = post("/api/v1/workflows/" + workflow.id() + "/runs", token,
                new StartRequest("hello"), StartedRunView.class);

        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID runId = started.getBody().id();
        RunView finished = awaitTerminal(member.id(), runId);
        assertThat(finished.status()).isEqualTo("completed");
        RunStepView read = finished.steps().get(0);
        assertThat(read.status()).isEqualTo("completed");
        assertThat(read.output()).isEqualTo("{\"folderId\":\"" + FOLDER + "\",\"total\":1,\"readable\":1,"
                + "\"files\":[{\"id\":\"f1\",\"name\":\"cv.pdf\",\"mimeType\":\"application/pdf\","
                + "\"detectedType\":\"pdf\",\"text\":\"cv text\",\"truncated\":false,\"needsVision\":false}]}");
        verify(drive).listFolder(member.id(), FOLDER, 20);
        verify(drive).readText(member.id(), cv);
        await(() -> balance(member) == 20);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM credit_ledger WHERE run_step_id = ?",
                Long.class, read.id())).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM credit_ledger WHERE run_id = ?", Long.class,
                runId)).isEqualTo(1);
    }

    @Test
    void aStepWithAnEmptyConfigIsRefusedAt400UnderItsConfigAndAValidOneIsAccepted() {
        UserView member = newUser("config-http");
        String token = loginAs(member);
        WorkflowView workflow = newWorkflow(member.id(), false);
        String steps = "/api/v1/workflows/" + workflow.id() + "/steps";

        ResponseEntity<ProblemView> refused = postJson(steps, token,
                "{\"type\":\"drive.read\",\"name\":\"read\",\"config\":{}}", ProblemView.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody().errors()).containsOnlyKeys("steps[0].config");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM workflow_step WHERE workflow_id = ?",
                Long.class, workflow.id())).isZero();

        ResponseEntity<String> created = postJson(steps, token,
                "{\"type\":\"drive.read\",\"name\":\"read\",\"config\":" + CONFIG + "}", String.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM workflow_step WHERE workflow_id = ? "
                + "AND type = 'drive.read'", Long.class, workflow.id())).isEqualTo(1);
    }

    @Test
    void anotherMemberCannotStartASharedWorkflowHoldingTheStepAndNoRunOrDriveReadHappens() {
        UserView author = newUser("author");
        UserView starter = memberWithBalance("starter", 30);
        WorkflowView workflow = newWorkflow(author.id(), true, new NewWorkflowStep("read", "drive.read", null, CONFIG),
                new NewWorkflowStep("probe", "echo", null));

        ResponseEntity<ProblemView> response = post("/api/v1/workflows/" + workflow.id() + "/runs",
                loginAs(starter), new StartRequest("hello"), ProblemView.class);

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody().code()).isEqualTo("CONNECTOR_STEP_NOT_OWNER");
        assertThat(response.getBody().errors()).containsOnlyKeys("step1");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM workflow_run WHERE workflow_id = ?",
                Long.class, workflow.id())).isZero();
        assertThat(balance(starter)).isEqualTo(30);
        verify(drive, never()).listFolder(any(), any(), anyInt());
        verify(drive, never()).readText(any(), any());
    }

    @Test
    void withTheConnectorOffTheStartAnswers503ConnectorTurnedOffAndNoRunIsCreated() {
        UserView member = memberWithBalance("owner-off", 30);
        WorkflowView workflow = newWorkflow(member.id(), false, new NewWorkflowStep("read", "drive.read", null, CONFIG),
                new NewWorkflowStep("probe", "echo", null));
        when(drive.enabled()).thenReturn(false);

        ResponseEntity<ProblemView> response = post("/api/v1/workflows/" + workflow.id() + "/runs",
                loginAs(member), new StartRequest("hello"), ProblemView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().code()).isEqualTo("connector_turned_off");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM workflow_run WHERE workflow_id = ?",
                Long.class, workflow.id())).isZero();
        assertThat(balance(member)).isEqualTo(30);
        verify(drive, never()).listFolder(any(), any(), anyInt());
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
