package com.flowcortex.app.credit;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.app.workflow.AbstractWorkflowHttpIT;
import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.WorkflowView;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** Run credits at their default (off): a dedicated context, since the switch binds at startup. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = "flowcortex.workflow.reaper-interval-ms=3600000")
@AutoConfigureTestRestTemplate
class CreditsSwitchedOffIT extends AbstractWorkflowHttpIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    record StartRequest(String input, Boolean acknowledgeCost) {
    }

    record ProblemView(int status, String detail) {
    }

    record PricedWorkflowView(UUID id, Long estimatedCredits) {
    }

    record ChargedRunView(UUID id, String status, Long chargedCredits) {
    }

    @Test
    void anEmptyWalletStillStartsARunThatChargesNothingAndNoPricesAreShown() {
        UserView member = newUser("credits-off");
        String token = loginAs(member);
        awaitWallet(member.id());
        jdbcTemplate.update("UPDATE credit_wallet SET balance = 0 WHERE user_id = ?", member.id());
        WorkflowView workflow = newWorkflow(member.id(), false, new NewWorkflowStep("draft", "echo", null),
                new NewWorkflowStep("break", "fail", null));

        ResponseEntity<ChargedRunView> started = post("/api/v1/workflows/" + workflow.id() + "/runs", token,
                new StartRequest("hello", null), ChargedRunView.class);

        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(started.getBody().chargedCredits()).isNull();
        UUID runId = started.getBody().id();
        pollUntil(member.id(), runId, run -> "error".equals(run.status()), Duration.ofSeconds(10));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM credit_ledger WHERE run_id = ?", Long.class,
                runId)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM credit_wallet WHERE user_id = ?", Long.class,
                member.id())).isZero();
        assertThat(get("/api/v1/runs/" + runId, token, ChargedRunView.class).getBody().chargedCredits()).isNull();
        assertThat(get("/api/v1/workflows/" + workflow.id(), token, PricedWorkflowView.class).getBody()
                .estimatedCredits()).isNull();
    }

    @Test
    void theBalanceIsRefusedWith503() {
        String token = loginAs(newUser("credits-off-balance"));

        ResponseEntity<ProblemView> response = get("/api/v1/credits/balance", token, ProblemView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().detail()).isEqualTo("This feature is turned off");
    }

    /** The signup grant still runs with the switch off, so the wallet is there when it is turned on. */
    private void awaitWallet(UUID userId) {
        Instant deadline = Instant.now().plusSeconds(10);
        while (jdbcTemplate.queryForObject("SELECT count(*) FROM credit_wallet WHERE user_id = ?", Long.class,
                userId) == 0) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("No wallet for user " + userId + " within 10 s");
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for the wallet", e);
            }
        }
    }
}
