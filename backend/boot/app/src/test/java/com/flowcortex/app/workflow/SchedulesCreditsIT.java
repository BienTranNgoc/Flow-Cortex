package com.flowcortex.app.workflow;

import static com.flowcortex.app.workflow.SchedulesEndpointsIT.call;
import static com.flowcortex.app.workflow.SchedulesEndpointsIT.schedulePath;
import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** A run-now the credit guard refuses, with credits on: the fire is recorded and its message follows the reader. */
@SuppressWarnings({"unchecked", "rawtypes"})
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "flowcortex.workflow.schedules.enabled=true",
        "flowcortex.credits.enabled=true",
        "flowcortex.workflow.reaper-interval-ms=3600000"})
@AutoConfigureTestRestTemplate
class SchedulesCreditsIT extends AbstractWorkflowHttpIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void runNowWithZeroBalanceIs402AndRecordsAFailedFire() {
        Scenario scenario = scenario("sched-credits");

        ResponseEntity<Map> refused = call(restTemplate, HttpMethod.POST, scenario.path() + "/run-now",
                scenario.token(), null, null);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(refused.getBody()).containsEntry("code", "INSUFFICIENT_CREDITS");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_run WHERE workflow_id = ?", Long.class,
                scenario.workflowId())).isZero();
        Map<String, Object> newest = fires(scenario, null).getFirst();
        assertThat(newest).containsEntry("trigger", "manual").containsEntry("status", "failed")
                .containsEntry("errorCode", "INSUFFICIENT_CREDITS").containsEntry("runId", null);
        assertThat(newest.get("errorMessage")).isEqualTo(refused.getBody().get("detail"));
        Map<String, Object> stored = jdbc.queryForMap("SELECT error_key, error_args::text AS args "
                + "FROM workflow_schedule_fire WHERE workflow_id = ?", scenario.workflowId());
        assertThat(stored).containsEntry("error_key", "credits.insufficient");
    }

    @Test
    void failedFireMessageIsResolvedInTheReadersLanguage() {
        Scenario scenario = scenario("sched-credits-lang");
        ResponseEntity<Map> refusedInEnglish = call(restTemplate, HttpMethod.POST, scenario.path() + "/run-now",
                scenario.token(), null, "en");
        ResponseEntity<Map> refusedInVietnamese = call(restTemplate, HttpMethod.POST, scenario.path() + "/run-now",
                scenario.token(), null, "vi");

        List<Map<String, Object>> asVietnamese = fires(scenario, "vi");
        List<Map<String, Object>> asEnglish = fires(scenario, "en");

        String english = (String) refusedInEnglish.getBody().get("detail");
        String vietnamese = (String) refusedInVietnamese.getBody().get("detail");
        assertThat(english).startsWith("Not enough credits");
        assertThat(vietnamese).startsWith("Không đủ credit");
        assertThat(asVietnamese).extracting(fire -> fire.get("errorMessage")).containsExactly(vietnamese, vietnamese);
        assertThat(asEnglish).extracting(fire -> fire.get("errorMessage")).containsExactly(english, english);
    }

    private record Scenario(UUID workflowId, String token) {
        String path() {
            return schedulePath(workflowId);
        }
    }

    private Scenario scenario(String prefix) {
        UserView member = newUser(prefix);
        awaitWallet(member.id());
        jdbc.update("UPDATE credit_wallet SET balance = 0 WHERE user_id = ?", member.id());
        UUID workflowId = newWorkflow(member.id(), false, new NewWorkflowStep("only", "echo", null)).id();
        String token = loginAs(member);
        ResponseEntity<Map> set = call(restTemplate, HttpMethod.PUT, schedulePath(workflowId), token,
                Map.of("frequency", "daily", "time", "08:00", "input", "digest"), null);
        assertThat(set.getStatusCode()).isEqualTo(HttpStatus.OK);
        return new Scenario(workflowId, token);
    }

    private List<Map<String, Object>> fires(Scenario scenario, String language) {
        ResponseEntity<Map> history = call(restTemplate, HttpMethod.GET, scenario.path() + "/runs", scenario.token(),
                null, language);
        assertThat(history.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>) history.getBody().get("items");
    }

    /** The signup grant runs after the account commits, on another thread; it creates the wallet. */
    private void awaitWallet(UUID userId) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (jdbc.queryForObject("SELECT count(*) FROM credit_wallet WHERE user_id = ?", Long.class,
                    userId) == 1) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for the wallet of " + userId, e);
            }
        }
        throw new AssertionError("No credit wallet for " + userId + " after 10 s");
    }
}
