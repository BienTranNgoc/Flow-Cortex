package com.flowcortex.app.drive;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.app.workflow.AbstractWorkflowHttpIT;
import com.flowcortex.drive.memory.MemoryPopulation;
import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.RunView;
import com.flowcortex.workflow.api.WorkflowView;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Memory end to end with spaces on: a real {@code echo} run completes and populates the owner's
 * personal space through the publication registry; list, pin, hide and text go over HTTP with a real login.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "flowcortex.spaces.enabled=true")
@AutoConfigureTestRestTemplate
class DriveMemoryIT extends AbstractWorkflowHttpIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final String MEDIA_BUCKET_NAME = "flowcortex-media";
    private static final long MIB = 1_048_576L;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MemoryPopulation population;

    record EntryView(UUID id, UUID runId, int stepOrder, String kind, String title, String previewText,
            String fileName, String mimeType, long sizeBytes, Instant resultAt, boolean pinned, String url,
            Instant urlExpiresAt) {
    }

    record ListView(List<EntryView> items, long total, int page, int pageSize, List<EntryView> pinned,
            Map<String, Long> sizeBuckets) {
    }

    record TextView(String text) {
    }

    record ProblemView(int status, String detail, Map<String, String> errors) {
    }

    @Test
    void aCompletedRunAppearsInItsOwnersMemory() {
        UserView owner = newUser("memory-live");
        UserView other = newUser("memory-other");
        UUID space = awaitSpace(owner.id());
        UUID runId = completedEchoRun(owner, "hello memory");

        awaitEntries(runId, 1);

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM memory_result WHERE run_id = ?", runId);
        assertThat(row.get("space_id")).isEqualTo(space);
        assertThat(row.get("owner_id")).isEqualTo(owner.id());
        assertThat(row.get("kind")).isEqualTo("text");
        assertThat(row.get("preview_text")).isEqualTo("hello memory");
        assertThat(row.get("pinned")).isEqualTo(false);
        ListView mine = list(loginAs(owner), "").getBody();
        assertThat(mine.items()).extracting(EntryView::runId).containsExactly(runId);
        awaitSpace(other.id());
        assertThat(list(loginAs(other), "").getBody().items()).isEmpty();
    }

    @Test
    void repopulatingKeepsPinnedAndHiddenAndRemovesStaleEntries() {
        UserView owner = newUser("memory-repop");
        UUID space = awaitSpace(owner.id());
        UUID runId = completedEchoRun(owner, "keep me");
        awaitEntries(runId, 1);
        UUID kept = jdbc.queryForObject("SELECT id FROM memory_result WHERE run_id = ?", UUID.class, runId);
        jdbc.update("UPDATE memory_result SET pinned = true, hidden = true WHERE id = ?", kept);
        UUID stale = seed(space, owner.id(), runId, "stale/key.mp4", "video", "Stale", 10, Instant.now());

        MemoryPopulation.Outcome outcome = population.populate(runId).orElseThrow();

        assertThat(outcome.removed()).isEqualTo(1);
        assertThat(outcome.inserted()).isZero();
        assertThat(jdbc.queryForList("SELECT id FROM memory_result WHERE run_id = ?", UUID.class, runId))
                .containsExactly(kept);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM memory_result WHERE id = ?", Long.class, stale)).isZero();
        Map<String, Object> row = jdbc.queryForMap("SELECT pinned, hidden FROM memory_result WHERE id = ?", kept);
        assertThat(row).containsEntry("pinned", true).containsEntry("hidden", true);
    }

    @Test
    void anIdenticalRepopulationLeavesUpdatedAtUnchanged() {
        UserView owner = newUser("memory-same");
        awaitSpace(owner.id());
        UUID runId = completedEchoRun(owner, "same again");
        awaitEntries(runId, 1);
        Timestamp before = jdbc.queryForObject("SELECT updated_at FROM memory_result WHERE run_id = ?",
                Timestamp.class, runId);

        MemoryPopulation.Outcome outcome = population.populate(runId).orElseThrow();

        assertThat(outcome.inserted()).isZero();
        assertThat(outcome.updated()).isZero();
        assertThat(outcome.removed()).isZero();
        assertThat(jdbc.queryForObject("SELECT updated_at FROM memory_result WHERE run_id = ?", Timestamp.class,
                runId)).isEqualTo(before);
    }

    @Test
    void listFiltersSortsPagesAndCountsBuckets() {
        UserView owner = newUser("memory-list");
        UUID space = awaitSpace(owner.id());
        UUID run = UUID.randomUUID();
        Instant now = Instant.now();
        seed(space, owner.id(), run, "a/small.mp4", "video", "Small clip", 500 * 1024, now.minusSeconds(40));
        seed(space, owner.id(), run, "b/mid.mp4", "video", "Mid clip", 10 * MIB, now.minusSeconds(30));
        UUID big = seed(space, owner.id(), run, "c/big.mp4", "video", "Big clip", 60 * MIB, now.minusSeconds(20));
        seed(space, owner.id(), run, "d/promo.png", "image", "Promo 50% off", MIB, now.minusSeconds(10));
        seed(space, owner.id(), run, "e/promo2.png", "image", "Promo 500 off", 52_428_800L, now.minusSeconds(5));
        UUID hidden = seed(space, owner.id(), run, "f/hidden.png", "image", "Hidden one", 5, now);
        jdbc.update("UPDATE memory_result SET hidden = true WHERE id = ?", hidden);
        jdbc.update("UPDATE memory_result SET pinned = true WHERE id = ?", big);
        String token = loginAs(owner);

        ListView videos = list(token, "?type=video&size=gt50mb").getBody();
        assertThat(videos.items()).extracting(EntryView::id).containsExactly(big);
        assertThat(videos.total()).isEqualTo(1);
        assertThat(videos.sizeBuckets()).containsEntry("lt1mb", 1L).containsEntry("1to50mb", 1L)
                .containsEntry("gt50mb", 1L);
        assertThat(videos.items().get(0).url()).isNotBlank();
        assertThat(videos.items().get(0).urlExpiresAt()).isAfter(Instant.now());

        ListView all = list(token, "").getBody();
        assertThat(all.total()).isEqualTo(5);
        assertThat(all.items()).extracting(EntryView::title).doesNotContain("Hidden one");
        assertThat(all.items().get(0).title()).isEqualTo("Promo 500 off");
        assertThat(all.sizeBuckets()).containsEntry("lt1mb", 1L).containsEntry("1to50mb", 3L)
                .containsEntry("gt50mb", 1L);
        assertThat(all.pinned()).extracting(EntryView::id).containsExactly(big);

        org.springframework.http.HttpHeaders bearer = new org.springframework.http.HttpHeaders();
        bearer.setBearerAuth(token);
        ResponseEntity<ListView> searched = restTemplate.exchange("/api/v1/drive/memory?q={q}", HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(bearer), ListView.class, "50%");
        assertThat(searched.getBody().items()).extracting(EntryView::title).containsExactly("Promo 50% off");
        assertThat(list(token, "?sort=size_asc&pageSize=2&page=1").getBody().items())
                .extracting(EntryView::title).containsExactly("Mid clip", "Promo 500 off");
        assertThat(list(token, "?sort=oldest").getBody().items().get(0).title()).isEqualTo("Small clip");
        assertThat(list(token, "?time=7d&type=image").getBody().total()).isEqualTo(2);
        assertThat(list(token, "?type=image&type=video").getBody().total()).isEqualTo(5);
        ListView manySizes = list(token, "?size=lt1mb&size=gt50mb").getBody();
        assertThat(manySizes.items()).extracting(EntryView::title).containsExactlyInAnyOrder("Small clip",
                "Big clip");
        assertThat(manySizes.sizeBuckets()).containsEntry("1to50mb", 3L);

        ResponseEntity<ProblemView> tooBig = get("/api/v1/drive/memory?pageSize=101", token, ProblemView.class);
        assertThat(tooBig.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(tooBig.getBody().errors()).containsKey("pageSize");
        ResponseEntity<ProblemView> reversed = get(
                "/api/v1/drive/memory?time=custom&from=2026-02-01T00:00:00Z&to=2026-01-01T00:00:00Z", token,
                ProblemView.class);
        assertThat(reversed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(reversed.getBody().errors()).containsKey("from");
        assertThat(get("/api/v1/drive/memory?type=pdf", token, ProblemView.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/api/v1/drive/memory?page=-1", token, ProblemView.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void pinTwiceAndHideAreIdempotentAndHiddenLeavesThePinnedStrip() {
        UserView owner = newUser("memory-flags");
        UUID space = awaitSpace(owner.id());
        UUID runId = completedEchoRun(owner, "flags");
        awaitEntries(runId, 1);
        UUID entry = jdbc.queryForObject("SELECT id FROM memory_result WHERE run_id = ?", UUID.class, runId);
        String token = loginAs(owner);
        String path = "/api/v1/drive/memory/" + entry;

        assertThat(send(HttpMethod.POST, path + "/pin", token).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(send(HttpMethod.POST, path + "/pin", token).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(list(token, "").getBody().pinned()).extracting(EntryView::id).containsExactly(entry);
        assertThat(send(HttpMethod.DELETE, path + "/pin", token).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(list(token, "").getBody().pinned()).isEmpty();
        send(HttpMethod.POST, path + "/pin", token);

        assertThat(send(HttpMethod.POST, path + "/hide", token).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(send(HttpMethod.POST, path + "/hide", token).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        ListView afterHide = list(token, "").getBody();
        assertThat(afterHide.items()).isEmpty();
        assertThat(afterHide.pinned()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM workflow_run WHERE id = ?", String.class, runId))
                .isEqualTo("completed");
        assertThat(space).isNotNull();
    }

    @Test
    void theTextRouteReturnsTheFullStepOutputAndAMediaEntryIs404() {
        UserView owner = newUser("memory-text");
        UUID space = awaitSpace(owner.id());
        String longText = "x".repeat(4000);
        UUID runId = completedEchoRun(owner, longText);
        awaitEntries(runId, 1);
        UUID entry = jdbc.queryForObject("SELECT id FROM memory_result WHERE run_id = ?", UUID.class, runId);
        UUID media = seed(space, owner.id(), UUID.randomUUID(), "m/clip.mp4", "video", "Clip", MIB, Instant.now());
        String token = loginAs(owner);

        ListView listed = list(token, "").getBody();
        assertThat(listed.items().stream().filter(e -> e.id().equals(entry)).findFirst().orElseThrow()
                .previewText()).hasSize(500);
        ResponseEntity<TextView> text = get("/api/v1/drive/memory/" + entry + "/text", token, TextView.class);
        assertThat(text.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(text.getBody().text()).isEqualTo(longText);
        assertThat(get("/api/v1/drive/memory/" + media + "/text", token, ProblemView.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void anotherMembersEntryAndAnUnknownIdAre404Alike() {
        UserView owner = newUser("memory-owner");
        UserView intruder = newUser("memory-intruder");
        UUID space = awaitSpace(owner.id());
        awaitSpace(intruder.id());
        UUID entry = seed(space, owner.id(), UUID.randomUUID(), "z/o.png", "image", "Mine", 5, Instant.now());
        String token = loginAs(intruder);

        for (String suffix : List.of("/text", "/pin", "/hide")) {
            ResponseEntity<ProblemView> foreign = exchange(suffix.equals("/text") ? HttpMethod.GET : HttpMethod.POST,
                    "/api/v1/drive/memory/" + entry + suffix, token);
            ResponseEntity<ProblemView> unknown = exchange(suffix.equals("/text") ? HttpMethod.GET : HttpMethod.POST,
                    "/api/v1/drive/memory/" + UUID.randomUUID() + suffix, token);
            assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(foreign.getBody().detail()).isEqualTo(unknown.getBody().detail());
        }
        assertThat(jdbc.queryForObject("SELECT pinned OR hidden FROM memory_result WHERE id = ?", Boolean.class,
                entry)).isFalse();
    }

    /** Spaces off is the default: entries are still written, the route answers 503. */
    @SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
    @AutoConfigureTestRestTemplate
    static class SpacesOff extends AbstractWorkflowHttpIT {

        @Autowired
        JdbcTemplate jdbc;

        @Test
        void withSpacesOffEntriesAreStillWrittenButTheRouteAnswers503() {
            UserView owner = newUser("memory-off");
            WorkflowView workflow = newWorkflow(owner.id(), false, new NewWorkflowStep("only", "echo", null));
            RunView run = workflowRunService.startRun(owner.id(), workflow.id(), "written anyway");
            awaitTerminal(owner.id(), run.id());

            await(() -> jdbc.queryForObject("SELECT count(*) FROM memory_result WHERE run_id = ?", Long.class,
                    run.id()) == 1);

            UUID space = jdbc.queryForObject("SELECT id FROM space WHERE owner_id = ? AND is_default", UUID.class,
                    owner.id());
            assertThat(jdbc.queryForObject("SELECT space_id FROM memory_result WHERE run_id = ?", UUID.class,
                    run.id())).isEqualTo(space);
            ResponseEntity<ProblemView> response = get("/api/v1/drive/memory", loginAs(owner), ProblemView.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }

        private static void await(BooleanSupplier condition) {
            Instant deadline = Instant.now().plus(TIMEOUT);
            while (!condition.getAsBoolean()) {
                assertThat(Instant.now()).as("condition within %s", TIMEOUT).isBefore(deadline);
                sleep();
            }
        }
    }

    private UUID completedEchoRun(UserView owner, String input) {
        WorkflowView workflow = newWorkflow(owner.id(), false, new NewWorkflowStep("only", "echo", null));
        RunView run = workflowRunService.startRun(owner.id(), workflow.id(), input);
        awaitTerminal(owner.id(), run.id());
        return run.id();
    }

    private UUID awaitSpace(UUID ownerId) {
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (true) {
            List<UUID> spaces = jdbc.queryForList("SELECT id FROM space WHERE owner_id = ? AND is_default",
                    UUID.class, ownerId);
            if (!spaces.isEmpty()) {
                return spaces.get(0);
            }
            assertThat(Instant.now()).as("personal space within %s", TIMEOUT).isBefore(deadline);
            sleep();
        }
    }

    private void awaitEntries(UUID runId, long count) {
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (jdbc.queryForObject("SELECT count(*) FROM memory_result WHERE run_id = ?", Long.class, runId)
                != count) {
            assertThat(Instant.now()).as("%s entries for run %s within %s", count, runId, TIMEOUT).isBefore(deadline);
            sleep();
        }
    }

    private UUID seed(UUID space, UUID owner, UUID runId, String key, String kind, String title, long bytes,
            Instant at) {
        UUID id = UUID.randomUUID();
        boolean media = !kind.equals("text");
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO memory_result (id, space_id, owner_id, run_id, run_step_id, step_order, result_key, "
                + "kind, title, bucket, object_key, file_name, mime_type, size_bytes, result_at, created_at, "
                + "updated_at) VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, space, owner, runId, UUID.randomUUID(), key, kind, title, media ? MEDIA_BUCKET_NAME : null,
                media ? "runs/" + runId + "/" + key : null, media ? "out.bin" : null,
                media ? kind + "/x" : null, bytes, Timestamp.from(at), now, now);
        return id;
    }

    private ResponseEntity<ListView> list(String token, String query) {
        return get("/api/v1/drive/memory" + query, token, ListView.class);
    }

    private ResponseEntity<ProblemView> send(HttpMethod method, String path, String token) {
        return exchange(method, path, token);
    }

    private ResponseEntity<ProblemView> exchange(HttpMethod method, String path, String token) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(path, method, new org.springframework.http.HttpEntity<>(headers),
                ProblemView.class);
    }

    private static void sleep() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting", e);
        }
    }
}
