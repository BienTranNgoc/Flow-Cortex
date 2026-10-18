package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.RunView;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** The cancel switch at its default (off): a dedicated context, since the property binds at startup. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = "flowcortex.workflow.reaper-interval-ms=3600000")
@AutoConfigureTestRestTemplate
class RunCancellationSwitchedOffIT extends AbstractWorkflowHttpIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void stoppingIsRefusedWith503AndTheRunKeepsGoing() {
        UserView owner = newUser("cancel-off");
        String token = loginAs(owner);
        UUID runId = RunCancellationIT.seedRun(this, jdbcTemplate, owner, "running", "running", "pending");

        ResponseEntity<RunCancellationIT.ProblemView> response = post("/api/v1/runs/" + runId + "/cancel", token,
                null, RunCancellationIT.ProblemView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().detail()).isEqualTo("This feature is turned off");
        RunView after = workflowRunService.getRun(owner.id(), runId);
        assertThat(after.status()).isEqualTo("running");
        assertThat(after.steps()).extracting(step -> step.status()).containsExactly("running", "pending");
    }
}
