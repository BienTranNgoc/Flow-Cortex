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

/** The workflow kill switch off with schedules on: a run-now is refused by the start path and recorded. */
@SuppressWarnings({"unchecked", "rawtypes"})
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "flowcortex.workflow.schedules.enabled=true",
        "flowcortex.workflow.enabled=false",
        "flowcortex.workflow.reaper-interval-ms=3600000"})
@AutoConfigureTestRestTemplate
class SchedulesWorkflowDisabledIT extends AbstractWorkflowHttpIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void runNowWithWorkflowDisabledIs503AndRecordsAFailedFire() {
        UserView member = newUser("sched-disabled");
        UUID workflowId = newWorkflow(member.id(), false, new NewWorkflowStep("only", "echo", null)).id();
        String token = loginAs(member);
        String path = schedulePath(workflowId);
        call(restTemplate, HttpMethod.PUT, path, token, Map.of("frequency", "daily", "time", "08:00"), null);

        ResponseEntity<Map> refused = call(restTemplate, HttpMethod.POST, path + "/run-now", token, null, null);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(refused.getBody().get("detail")).isEqualTo("Workflow execution is currently disabled");
        List<Map<String, Object>> fires = (List<Map<String, Object>>) call(restTemplate, HttpMethod.GET,
                path + "/runs", token, null, null).getBody().get("items");
        // The test client retries a 503 once on its own, so the refused request may be recorded twice.
        assertThat(fires).isNotEmpty().allSatisfy(fire -> assertThat(fire).containsEntry("status", "failed")
                .containsEntry("trigger", "manual").containsEntry("errorCode", "workflow.disabled")
                .containsEntry("errorMessage", "Workflow execution is currently disabled"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_run WHERE workflow_id = ?", Long.class,
                workflowId)).isZero();
    }
}
