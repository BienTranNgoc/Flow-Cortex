package com.flowcortex.app.workflow;

import static com.flowcortex.app.workflow.SchedulesEndpointsIT.call;
import static com.flowcortex.app.workflow.SchedulesEndpointsIT.schedulePath;
import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
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

/** The schedules switch off, which is its default: a dedicated context, since the property binds at startup. */
@SuppressWarnings({"unchecked", "rawtypes"})
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "flowcortex.workflow.schedules.enabled=false",
        "flowcortex.workflow.reaper-interval-ms=3600000"})
@AutoConfigureTestRestTemplate
class SchedulesSwitchOffIT extends AbstractWorkflowHttpIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void switchOffAnswers503OnEveryWellFormedRouteAndStoresNothing() {
        UserView member = newUser("sched-off");
        UUID workflowId = newWorkflow(member.id(), false, new NewWorkflowStep("only", "echo", null)).id();
        String token = loginAs(member);
        String path = schedulePath(workflowId);
        Map<String, Object> valid = Map.of("frequency", "daily", "time", "08:00");

        Map<String, ResponseEntity<Map>> answers = new java.util.LinkedHashMap<>();
        answers.put("put", call(restTemplate, HttpMethod.PUT, path, token, valid, null));
        answers.put("get", call(restTemplate, HttpMethod.GET, path, token, null, null));
        answers.put("delete", call(restTemplate, HttpMethod.DELETE, path, token, null, null));
        answers.put("pause", call(restTemplate, HttpMethod.POST, path + "/pause", token, null, null));
        answers.put("resume", call(restTemplate, HttpMethod.POST, path + "/resume", token, null, null));
        answers.put("run-now", call(restTemplate, HttpMethod.POST, path + "/run-now", token, null, null));
        answers.put("history", call(restTemplate, HttpMethod.GET, path + "/runs", token, null, null));

        answers.forEach((route, response) -> {
            assertThat(response.getStatusCode()).as(route).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(response.getBody().get("detail")).as(route).isEqualTo("This feature is turned off");
        });
        assertThat(count("workflow_schedule", workflowId)).isZero();
        assertThat(count("workflow_schedule_fire", workflowId)).isZero();
        assertThat(count("workflow_run", workflowId)).isZero();
    }

    @Test
    void aMalformedBodyIsStillTheValidation400AndStoresNothing() {
        UserView member = newUser("sched-off-invalid");
        UUID workflowId = newWorkflow(member.id(), false, new NewWorkflowStep("only", "echo", null)).id();

        ResponseEntity<Map> response = call(restTemplate, HttpMethod.PUT, schedulePath(workflowId),
                loginAs(member), Map.of("frequency", "daily", "time", "08:10"), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((Map<String, Object>) response.getBody().get("errors")).containsKey("time");
        assertThat(count("workflow_schedule", workflowId)).isZero();
    }

    private long count(String table, UUID workflowId) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE workflow_id = ?", Long.class,
                workflowId);
    }
}
