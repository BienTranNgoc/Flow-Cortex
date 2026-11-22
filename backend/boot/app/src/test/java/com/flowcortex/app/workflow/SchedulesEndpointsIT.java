package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code /api/v1/workflows/{id}/schedule} with the switch on, against the real app. The app's clock can be pinned
 * to an instant after today (a token is issued by the real clock before the pin, and a later instant never
 * expires one), so the next fire of a schedule is asserted exactly.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "flowcortex.workflow.schedules.enabled=true",
        "flowcortex.workflow.reaper-interval-ms=3600000"})
@AutoConfigureTestRestTemplate
@Import(SchedulesEndpointsIT.Clocks.class)
class SchedulesEndpointsIT extends AbstractWorkflowHttpIT {

    /** A Saturday, 09:00 in Asia/Ho_Chi_Minh. */
    private static final Instant SATURDAY_0900 = Instant.parse("2030-11-16T02:00:00Z");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PinnedClock clock;

    @AfterEach
    void unpin() {
        clock.unpin();
    }

    @Test
    void putCreatesThenReplacesOneSchedule() {
        UserView member = newUser("sched-put");
        UUID workflowId = textWorkflow(member);
        String token = loginAs(member);
        clock.pin(SATURDAY_0900);

        ResponseEntity<Map> created = put(workflowId, token, weekly("monday", "08:00", "daily digest"));
        ResponseEntity<Map> replaced = put(workflowId, token, Map.of("frequency", "daily", "dayOfWeek", "friday",
                "time", "21:30"));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> first = created.getBody();
        assertThat(first).containsEntry("status", "active").containsEntry("frequency", "weekly")
                .containsEntry("dayOfWeek", "monday").containsEntry("dayOfMonth", null)
                .containsEntry("timezone", "Asia/Ho_Chi_Minh").containsEntry("input", "daily digest")
                .containsEntry("time", "08:00").containsEntry("pausedAt", null)
                .containsEntry("acknowledgeCost", false).containsEntry("workflowId", workflowId.toString());
        assertThat(first.get("nextRunLocal")).isEqualTo("2030-11-18T08:00:00+07:00");
        assertThat(Instant.parse((String) first.get("nextRunAt"))).isEqualTo(Instant.parse("2030-11-18T01:00:00Z"));
        assertThat(replaced.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replaced.getBody()).containsEntry("id", first.get("id")).containsEntry("frequency", "daily")
                .containsEntry("dayOfWeek", null).containsEntry("time", "21:30")
                .containsEntry("nextRunLocal", "2030-11-16T21:30:00+07:00");
        assertThat(scheduleCount(workflowId)).isEqualTo(1);
    }

    @Test
    void aDailyTimeAlreadyPassedTodayAndOneExactlyNowAreTomorrow() {
        UserView member = newUser("sched-daily");
        UUID workflowId = textWorkflow(member);
        String token = loginAs(member);
        clock.pin(SATURDAY_0900);

        ResponseEntity<Map> passed = put(workflowId, token, Map.of("frequency", "daily", "time", "08:00"));
        ResponseEntity<Map> exactlyNow = put(workflowId, token, Map.of("frequency", "daily", "time", "09:00"));
        ResponseEntity<Map> utc = put(workflowId, token,
                Map.of("frequency", "daily", "time", "02:00", "timezone", "UTC"));

        assertThat(passed.getBody()).containsEntry("nextRunLocal", "2030-11-17T08:00:00+07:00");
        assertThat(exactlyNow.getBody()).containsEntry("nextRunLocal", "2030-11-17T09:00:00+07:00");
        assertThat(utc.getBody()).containsEntry("nextRunLocal", "2030-11-17T02:00:00Z");
    }

    @Test
    void activeFalseCreatesAPausedScheduleWithNoNextRun() {
        UserView member = newUser("sched-paused-create");
        UUID workflowId = textWorkflow(member);
        String token = loginAs(member);
        clock.pin(SATURDAY_0900);

        ResponseEntity<Map> response = put(workflowId, token,
                Map.of("frequency", "daily", "time", "08:00", "active", false));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "paused").containsEntry("nextRunAt", null)
                .containsEntry("nextRunLocal", null);
        assertThat(Instant.parse((String) response.getBody().get("pausedAt"))).isEqualTo(SATURDAY_0900);
    }

    @Test
    void offGridTimeMissingDayUnknownZoneAndUnknownFrequencyAre400KeyedByFieldWithNothingStored() {
        UserView member = newUser("sched-invalid");
        UUID workflowId = textWorkflow(member);
        String token = loginAs(member);

        Map<String, Object> offGrid = errors(put(workflowId, token, Map.of("frequency", "daily", "time", "08:10")));
        Map<String, Object> monthlyWithoutDay = errors(put(workflowId, token,
                Map.of("frequency", "monthly", "time", "09:00")));
        Map<String, Object> dayOutOfRange = errors(put(workflowId, token,
                Map.of("frequency", "monthly", "dayOfMonth", 29, "time", "09:00")));
        Map<String, Object> weeklyWithoutDay = errors(put(workflowId, token,
                Map.of("frequency", "weekly", "time", "09:00")));
        Map<String, Object> unknownDay = errors(put(workflowId, token,
                Map.of("frequency", "weekly", "dayOfWeek", "someday", "time", "09:00")));
        Map<String, Object> unknownZone = errors(put(workflowId, token,
                Map.of("frequency", "daily", "time", "09:00", "timezone", "Mars/Olympus")));
        Map<String, Object> offsetZone = errors(put(workflowId, token,
                Map.of("frequency", "daily", "time", "09:00", "timezone", "+07:00")));
        Map<String, Object> unknownFrequency = errors(put(workflowId, token,
                Map.of("frequency", "hourly", "time", "09:00")));
        Map<String, Object> longInput = errors(put(workflowId, token,
                Map.of("frequency", "daily", "time", "09:00", "input", "x".repeat(10_001))));
        Map<String, Object> nothing = errors(put(workflowId, token, Map.of()));

        assertThat(offGrid).containsOnlyKeys("time");
        assertThat(monthlyWithoutDay).containsOnlyKeys("dayOfMonth");
        assertThat(dayOutOfRange).containsOnlyKeys("dayOfMonth");
        assertThat(weeklyWithoutDay).containsOnlyKeys("dayOfWeek");
        assertThat(unknownDay).containsOnlyKeys("dayOfWeek");
        assertThat(unknownZone).containsOnlyKeys("timezone");
        assertThat(offsetZone).containsOnlyKeys("timezone");
        assertThat(unknownFrequency).containsOnlyKeys("frequency");
        assertThat(longInput).containsOnlyKeys("input");
        assertThat(nothing).containsOnlyKeys("frequency", "time");
        assertThat(offGrid.get("time")).isEqualTo("Enter a time as HH:mm on a quarter hour: 00, 15, 30 or 45");
        assertThat(scheduleCount(workflowId)).isZero();
    }

    @Test
    void mediaFirstWorkflowIs422TextInputOnly() {
        UserView member = newUser("sched-media");
        UUID workflowId = newWorkflow(member.id(), false, new NewWorkflowStep("ingest", "probe", null)).id();

        ResponseEntity<Map> response = put(workflowId, loginAs(member),
                Map.of("frequency", "daily", "time", "08:00"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody().get("detail")).asString().startsWith("This workflow starts from an uploaded file");
        assertThat(scheduleCount(workflowId)).isZero();
    }

    @Test
    void incompleteWorkflowIs422WhenActiveAndAcceptedWhenPaused() {
        UserView member = newUser("sched-incomplete");
        UUID workflowId = newWorkflow(member.id(), false).id();
        String token = loginAs(member);

        ResponseEntity<Map> active = put(workflowId, token, Map.of("frequency", "daily", "time", "08:00"));
        ResponseEntity<Map> paused = put(workflowId, token,
                Map.of("frequency", "daily", "time", "08:00", "active", false));

        assertThat(active.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(active.getBody()).containsEntry("code", "WORKFLOW_INCOMPLETE");
        assertThat(paused.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(paused.getBody()).containsEntry("status", "paused");
    }

    @Test
    void nonOwnerIsCheckedAgainstThePublishedVersionNotTheDraft() {
        UserView owner = newUser("sched-pointer-owner");
        UserView member = newUser("sched-pointer-member");
        UUID workflowId = textWorkflow(owner);
        publish(workflowId, loginAs(owner));
        jdbc.update("UPDATE workflow_step SET type = 'probe' WHERE workflow_id = ?", workflowId);

        ResponseEntity<Map> response = put(workflowId, loginAs(member),
                Map.of("frequency", "daily", "time", "08:00"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "active");
    }

    @Test
    void nonOwnerPublishedVersionNeedingAnUploadIs422() {
        UserView owner = newUser("sched-upload-owner");
        UserView member = newUser("sched-upload-member");
        UUID workflowId = newWorkflow(owner.id(), false, new NewWorkflowStep("ingest", "probe", null)).id();
        publish(workflowId, loginAs(owner));
        jdbc.update("UPDATE workflow_step SET type = 'echo' WHERE workflow_id = ?", workflowId);

        ResponseEntity<Map> response = put(workflowId, loginAs(member),
                Map.of("frequency", "daily", "time", "08:00"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody().get("detail")).asString().startsWith("This workflow starts from an uploaded file");
        assertThat(scheduleCount(workflowId)).isZero();
    }

    @Test
    void anotherMembersDraftIs404() {
        UserView owner = newUser("sched-draft-owner");
        UserView stranger = newUser("sched-draft-stranger");
        UUID workflowId = textWorkflow(owner);

        ResponseEntity<Map> response = put(workflowId, loginAs(stranger),
                Map.of("frequency", "daily", "time", "08:00"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("detail")).asString().startsWith("Workflow not found");
        assertThat(scheduleCount(workflowId)).isZero();
    }

    @Test
    void getWithoutScheduleIs404() {
        UserView member = newUser("sched-get-none");
        UUID workflowId = textWorkflow(member);

        ResponseEntity<Map> response = call(HttpMethod.GET, schedulePath(workflowId), loginAs(member), null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("detail")).asString().startsWith("No schedule found for this workflow");
    }

    @Test
    void pauseAndResumeAreIdempotentAndRecomputeNextRun() {
        UserView member = newUser("sched-pause");
        UUID workflowId = textWorkflow(member);
        String token = loginAs(member);
        String stranger = loginAs(newUser("sched-pause-none"));
        clock.pin(SATURDAY_0900);
        put(workflowId, token, Map.of("frequency", "daily", "time", "08:00"));

        ResponseEntity<Map> paused = post(workflowId, "/pause", token);
        clock.pin(SATURDAY_0900.plusSeconds(3600));
        ResponseEntity<Map> pausedAgain = post(workflowId, "/pause", token);
        ResponseEntity<Map> resumed = post(workflowId, "/resume", token);
        clock.pin(SATURDAY_0900.plusSeconds(7200));
        ResponseEntity<Map> resumedAgain = post(workflowId, "/resume", token);

        assertThat(paused.getBody()).containsEntry("status", "paused").containsEntry("nextRunAt", null);
        assertThat(Instant.parse((String) paused.getBody().get("pausedAt"))).isEqualTo(SATURDAY_0900);
        assertThat(pausedAgain.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pausedAgain.getBody().get("pausedAt")).isEqualTo(paused.getBody().get("pausedAt"));
        assertThat(resumed.getBody()).containsEntry("status", "active").containsEntry("pausedAt", null)
                .containsEntry("nextRunLocal", "2030-11-17T08:00:00+07:00");
        assertThat(resumedAgain.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resumedAgain.getBody().get("nextRunAt")).isEqualTo(resumed.getBody().get("nextRunAt"));
        assertThat(post(workflowId, "/pause", stranger).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(post(workflowId, "/resume", stranger).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void resumeAfterWorkflowMadePrivateIs404AndStaysPaused() {
        UserView owner = newUser("sched-private-owner");
        UserView member = newUser("sched-private-member");
        UUID workflowId = textWorkflow(owner);
        String ownerToken = loginAs(owner);
        String memberToken = loginAs(member);
        publish(workflowId, ownerToken);
        put(workflowId, memberToken, Map.of("frequency", "daily", "time", "08:00", "active", false));
        setVisibility(workflowId, ownerToken, "private");

        ResponseEntity<Map> resumed = post(workflowId, "/resume", memberToken);

        assertThat(resumed.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resumed.getBody().get("detail")).asString().startsWith("Workflow not found");
        assertThat(storedStatus(workflowId, member.id())).isEqualTo("paused");
    }

    @Test
    void resumeAfterLinkGrantClearedIs404AndStaysPaused() {
        UserView owner = newUser("sched-grant-owner");
        UserView member = newUser("sched-grant-member");
        UUID workflowId = textWorkflow(owner);
        String ownerToken = loginAs(owner);
        String memberToken = loginAs(member);
        String key = (String) setVisibility(workflowId, ownerToken, "unlisted").getBody().get("shareKey");
        assertThat(call(HttpMethod.GET, "/api/v1/workflows/" + workflowId + "?key=" + key, memberToken, null, null)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> set = put(workflowId, memberToken,
                Map.of("frequency", "daily", "time", "08:00", "active", false));
        setVisibility(workflowId, ownerToken, "private");

        ResponseEntity<Map> resumed = post(workflowId, "/resume", memberToken);

        assertThat(set.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resumed.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(storedStatus(workflowId, member.id())).isEqualTo("paused");
    }

    @Test
    void deleteIs204KeepsHistoryAndWorksAfterWorkflowDeleted() {
        UserView member = newUser("sched-delete");
        UUID workflowId = textWorkflow(member);
        String token = loginAs(member);
        put(workflowId, token, Map.of("frequency", "daily", "time", "08:00"));
        seedFire(member.id(), workflowId, "started", Instant.parse("2030-11-16T03:00:00Z"));
        seedFire(member.id(), workflowId, "started", Instant.parse("2030-11-16T04:00:00Z"));

        ResponseEntity<Map> deleted = call(HttpMethod.DELETE, schedulePath(workflowId), token, null, null);
        ResponseEntity<Map> read = call(HttpMethod.GET, schedulePath(workflowId), token, null, null);
        ResponseEntity<Map> history = call(HttpMethod.GET, schedulePath(workflowId) + "/runs", token, null, null);
        ResponseEntity<Map> deletedAgain = call(HttpMethod.DELETE, schedulePath(workflowId), token, null, null);

        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat((List<?>) history.getBody().get("items")).hasSize(2);
        assertThat(deletedAgain.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        UserView owner = newUser("sched-delete-owner");
        UUID shared = textWorkflow(owner);
        String ownerToken = loginAs(owner);
        publish(shared, ownerToken);
        put(shared, token, Map.of("frequency", "daily", "time", "08:00", "active", false));
        assertThat(call(HttpMethod.DELETE, "/api/v1/workflows/" + shared, ownerToken, null, null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(call(HttpMethod.DELETE, schedulePath(shared), token, null, null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void runNowStartsTheScheduleInputAndKeepsStatusAndNextRun() {
        UserView member = newUser("sched-run-now");
        UUID workflowId = textWorkflow(member);
        String token = loginAs(member);
        put(workflowId, token, Map.of("frequency", "daily", "time", "08:00", "input", "daily digest",
                "active", false));

        ResponseEntity<Map> run = post(workflowId, "/run-now", token);

        assertThat(run.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(run.getBody()).containsEntry("status", "running").containsEntry("input", "daily digest")
                .containsEntry("workflowId", workflowId.toString());
        Map<String, Object> schedule = call(HttpMethod.GET, schedulePath(workflowId), token, null, null).getBody();
        assertThat(schedule).containsEntry("status", "paused").containsEntry("nextRunAt", null);
        Map<String, Object> newest = (Map<String, Object>) ((List<?>) call(HttpMethod.GET,
                schedulePath(workflowId) + "/runs", token, null, null).getBody().get("items")).getFirst();
        assertThat(newest).containsEntry("trigger", "manual").containsEntry("status", "started")
                .containsEntry("runId", run.getBody().get("id")).containsEntry("errorCode", null)
                .containsEntry("errorMessage", null);
    }

    @Test
    void runNowWithoutAScheduleOrAfterTheWorkflowWasMadePrivateIs404() {
        UserView owner = newUser("sched-run-now-owner");
        UserView member = newUser("sched-run-now-member");
        UUID workflowId = textWorkflow(owner);
        String ownerToken = loginAs(owner);
        String memberToken = loginAs(member);
        publish(workflowId, ownerToken);

        ResponseEntity<Map> none = post(workflowId, "/run-now", memberToken);
        put(workflowId, memberToken, Map.of("frequency", "daily", "time", "08:00"));
        setVisibility(workflowId, ownerToken, "private");
        ResponseEntity<Map> hidden = post(workflowId, "/run-now", memberToken);

        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(none.getBody().get("detail")).asString().startsWith("No schedule found for this workflow");
        assertThat(hidden.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(hidden.getBody().get("detail")).asString().startsWith("Workflow not found");
        assertThat(fireCount(member.id(), workflowId)).isZero();
    }

    @Test
    void historyIsNewestFirstAndOwnOnly() {
        UserView owner = newUser("sched-history-owner");
        UserView member = newUser("sched-history-member");
        UUID workflowId = textWorkflow(owner);
        String ownerToken = loginAs(owner);
        String memberToken = loginAs(member);
        publish(workflowId, ownerToken);
        Instant ten = Instant.parse("2030-11-16T03:00:00Z");
        UUID tenFire = seedFire(member.id(), workflowId, "started", ten);
        UUID ownersFire = seedFire(owner.id(), workflowId, "started", ten.plusSeconds(1800));
        UUID elevenFire = seedFire(member.id(), workflowId, "started", ten.plusSeconds(3600));

        ResponseEntity<Map> history = call(HttpMethod.GET, schedulePath(workflowId) + "/runs", memberToken, null,
                null);
        ResponseEntity<Map> second = call(HttpMethod.GET, schedulePath(workflowId) + "/runs?page=1&size=1",
                memberToken, null, null);
        ResponseEntity<Map> tooLarge = call(HttpMethod.GET, schedulePath(workflowId) + "/runs?size=101",
                memberToken, null, null);
        ResponseEntity<Map> negativePage = call(HttpMethod.GET, schedulePath(workflowId) + "/runs?page=-1",
                memberToken, null, null);

        List<Map<String, Object>> items = (List<Map<String, Object>>) history.getBody().get("items");
        assertThat(items).extracting(item -> item.get("id"))
                .containsExactly(elevenFire.toString(), tenFire.toString());
        assertThat(items).extracting(item -> item.get("id")).doesNotContain(ownersFire.toString());
        assertThat(history.getBody()).containsEntry("total", 2).containsEntry("size", 20).containsEntry("page", 0);
        assertThat((List<Map<String, Object>>) second.getBody().get("items")).extracting(item -> item.get("id"))
                .containsExactly(tenFire.toString());
        assertThat(tooLarge.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((Map<String, Object>) tooLarge.getBody().get("errors")).containsKey("size");
        assertThat(negativePage.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((Map<String, Object>) negativePage.getBody().get("errors")).containsKey("page");
    }

    @Test
    void twoMembersOnAPublicWorkflowKeepIndependentSchedules() {
        UserView owner = newUser("sched-two-owner");
        UserView member = newUser("sched-two-member");
        UUID workflowId = textWorkflow(owner);
        String ownerToken = loginAs(owner);
        String memberToken = loginAs(member);
        publish(workflowId, ownerToken);
        put(workflowId, ownerToken, Map.of("frequency", "daily", "time", "07:00"));
        put(workflowId, memberToken, Map.of("frequency", "daily", "time", "09:00"));

        ResponseEntity<Map> paused = post(workflowId, "/pause", memberToken);
        ResponseEntity<Map> deleted = call(HttpMethod.DELETE, schedulePath(workflowId), memberToken, null, null);

        assertThat(paused.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        ResponseEntity<Map> ownersSchedule = call(HttpMethod.GET, schedulePath(workflowId), ownerToken, null, null);
        assertThat(ownersSchedule.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ownersSchedule.getBody()).containsEntry("status", "active").containsEntry("time", "07:00");
        assertThat(scheduleCount(workflowId)).isEqualTo(1);
    }

    private UUID textWorkflow(UserView owner) {
        return newWorkflow(owner.id(), false, new NewWorkflowStep("only", "echo", null)).id();
    }

    private void publish(UUID workflowId, String ownerToken) {
        assertThat(setVisibility(workflowId, ownerToken, "public").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<Map> setVisibility(UUID workflowId, String ownerToken, String visibility) {
        return call(HttpMethod.PATCH, "/api/v1/workflows/" + workflowId + "/visibility", ownerToken,
                Map.of("visibility", visibility), null);
    }

    private long scheduleCount(UUID workflowId) {
        return jdbc.queryForObject("SELECT count(*) FROM workflow_schedule WHERE workflow_id = ?", Long.class,
                workflowId);
    }

    private long fireCount(UUID ownerId, UUID workflowId) {
        return jdbc.queryForObject("SELECT count(*) FROM workflow_schedule_fire WHERE owner_id = ? "
                + "AND workflow_id = ?", Long.class, ownerId, workflowId);
    }

    private String storedStatus(UUID workflowId, UUID ownerId) {
        return jdbc.queryForObject("SELECT status FROM workflow_schedule WHERE workflow_id = ? AND owner_id = ?",
                String.class, workflowId, ownerId);
    }

    /** A fire row with no schedule behind it and no run holding its key, as a past crash or purge leaves one. */
    private UUID seedFire(UUID ownerId, UUID workflowId, String status, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO workflow_schedule_fire (id, owner_id, workflow_id, trigger, scheduled_at, status, "
                + "request_key, created_at) VALUES (?, ?, ?, 'manual', ?, ?, ?, ?)", id, ownerId, workflowId,
                java.sql.Timestamp.from(createdAt), status, "schedule:" + id, java.sql.Timestamp.from(createdAt));
        return id;
    }

    static Map<String, Object> weekly(String dayOfWeek, String time, String input) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("frequency", "weekly");
        body.put("dayOfWeek", dayOfWeek);
        body.put("time", time);
        body.put("input", input);
        return body;
    }

    static String schedulePath(UUID workflowId) {
        return "/api/v1/workflows/" + workflowId + "/schedule";
    }

    private ResponseEntity<Map> put(UUID workflowId, String token, Map<String, Object> body) {
        return call(HttpMethod.PUT, schedulePath(workflowId), token, body, null);
    }

    private ResponseEntity<Map> post(UUID workflowId, String suffix, String token) {
        return call(HttpMethod.POST, schedulePath(workflowId) + suffix, token, null, null);
    }

    private Map<String, Object> errors(ResponseEntity<Map> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        return (Map<String, Object>) response.getBody().get("errors");
    }

    private ResponseEntity<Map> call(HttpMethod method, String path, String token, Object body, String language) {
        return call(restTemplate, method, path, token, body, language);
    }

    static ResponseEntity<Map> call(TestRestTemplate restTemplate, HttpMethod method, String path, String token,
            Object body, String language) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        if (language != null) {
            headers.set(HttpHeaders.ACCEPT_LANGUAGE, language);
        }
        if (body != null) {
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        }
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), Map.class);
    }

    /** The system clock until a test pins it to an instant. */
    static final class PinnedClock extends Clock {

        private final AtomicReference<Instant> pinned = new AtomicReference<>();

        void pin(Instant at) {
            pinned.set(at);
        }

        void unpin() {
            pinned.set(null);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException("fixed to UTC");
        }

        @Override
        public Instant instant() {
            Instant at = pinned.get();
            return at == null ? Instant.now() : at;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Clocks {

        @Bean
        @Primary
        PinnedClock pinnedClock() {
            return new PinnedClock();
        }
    }
}
