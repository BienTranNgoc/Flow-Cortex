package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.jobs.api.ClusterJob;
import com.flowcortex.jobs.application.ClusterJobRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The discovery figures pass against real Postgres with a fixed clock. The database is shared with other ITs, so every
 * assertion reads the rows of the workflows made here, and every row made here is removed afterwards.
 */
@SpringBootTest
@Import(DiscoveryStatsJobIT.FixedClock.class)
class DiscoveryStatsJobIT extends AbstractPostgresBootTest {

    private static final String JOB = "workflow.discovery-stats";
    private static final Instant NOW = Instant.parse("2026-11-24T12:00:00Z");

    @Autowired
    private ClusterJobRegistry registry;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<UUID> workflows = new ArrayList<>();
    private final List<UUID> childRuns = new ArrayList<>();

    @AfterEach
    void removeWhatThisTestMade() {
        for (UUID id : childRuns) {
            jdbc.update("DELETE FROM workflow_run WHERE id = ?", id);
        }
        for (UUID id : workflows) {
            jdbc.update("DELETE FROM workflow_discovery_stats WHERE workflow_id = ?", id);
            jdbc.update("DELETE FROM workflow_bookmark WHERE workflow_id = ?", id);
            jdbc.update("DELETE FROM run_step WHERE run_id IN (SELECT id FROM workflow_run WHERE workflow_id = ?)", id);
            jdbc.update("DELETE FROM workflow_run WHERE workflow_id = ?", id);
            jdbc.update("DELETE FROM workflow WHERE id = ?", id);
        }
        jdbc.update("DELETE FROM workflow WHERE name LIKE 'dst-bulk-%'");
        workflows.clear();
        childRuns.clear();
    }

    @Test
    void ownerRunsAndSavesDoNotCount() {
        UUID owner = UUID.randomUUID();
        UUID chi = UUID.randomUUID();
        UUID w = catalogue(owner);
        for (int i = 0; i < 30; i++) {
            run(w, owner, "completed", NOW.minus(Duration.ofHours(i + 1)));
        }
        run(w, chi, "running", NOW.minus(Duration.ofDays(1)));
        run(w, chi, "running", NOW.minus(Duration.ofDays(2)));
        bookmark(owner, w, NOW.minus(Duration.ofDays(1)));

        runPass();

        assertThat(figures(w)).containsEntry("runners_7d", 1).containsEntry("saves_7d", 0)
                .containsEntry("members_30d", 0).containsEntry("trending_score", 3);
    }

    @Test
    void runnersAreDistinctMembers() {
        UUID w = catalogue(UUID.randomUUID());
        UUID chi = UUID.randomUUID();
        UUID dan = UUID.randomUUID();
        run(w, chi, "running", NOW.minus(Duration.ofDays(1)));
        run(w, chi, "completed", NOW.minus(Duration.ofDays(2)));
        run(w, chi, "error", NOW.minus(Duration.ofDays(3)));
        run(w, dan, "cancelled", NOW.minus(Duration.ofDays(1)));

        runPass();

        assertThat(figures(w)).containsEntry("runners_7d", 2);
    }

    @Test
    void membersCountOnlyCompletedRunsInThirtyDays() {
        UUID w = catalogue(UUID.randomUUID());
        UUID chi = UUID.randomUUID();
        run(w, chi, "completed", NOW.minus(Duration.ofDays(10)));
        run(w, chi, "completed", NOW.minus(Duration.ofDays(12)));
        run(w, UUID.randomUUID(), "running", NOW.minus(Duration.ofDays(1)));
        run(w, UUID.randomUUID(), "error", NOW.minus(Duration.ofDays(2)));
        run(w, UUID.randomUUID(), "cancelled", NOW.minus(Duration.ofDays(2)));
        run(w, UUID.randomUUID(), "completed", NOW.minus(Duration.ofDays(31)));

        runPass();

        assertThat(figures(w)).containsEntry("members_30d", 1).containsEntry("runners_7d", 3);
    }

    @Test
    void windowsEndAtSevenAndThirtyDays() {
        UUID w = catalogue(UUID.randomUUID());
        run(w, UUID.randomUUID(), "completed", NOW.minus(Duration.ofDays(7)).plusSeconds(1));
        run(w, UUID.randomUUID(), "completed", NOW.minus(Duration.ofDays(7)).minusSeconds(1));
        run(w, UUID.randomUUID(), "completed", NOW.minus(Duration.ofDays(30)).plusSeconds(1));
        run(w, UUID.randomUUID(), "completed", NOW.minus(Duration.ofDays(30)).minusSeconds(1));
        bookmark(UUID.randomUUID(), w, NOW.minus(Duration.ofDays(7)).plusSeconds(1));
        bookmark(UUID.randomUUID(), w, NOW.minus(Duration.ofDays(7)).minusSeconds(1));

        runPass();

        assertThat(figures(w)).containsEntry("runners_7d", 1).containsEntry("saves_7d", 1)
                .containsEntry("members_30d", 3);
    }

    @Test
    void trendingScoreStored() {
        UUID busy = catalogue(UUID.randomUUID());
        UUID saved = catalogue(UUID.randomUUID());
        for (int i = 0; i < 4; i++) {
            run(busy, UUID.randomUUID(), "running", NOW.minus(Duration.ofDays(1)));
        }
        bookmark(UUID.randomUUID(), busy, NOW.minus(Duration.ofDays(1)));
        for (int i = 0; i < 2; i++) {
            run(saved, UUID.randomUUID(), "running", NOW.minus(Duration.ofDays(1)));
        }
        for (int i = 0; i < 3; i++) {
            bookmark(UUID.randomUUID(), saved, NOW.minus(Duration.ofDays(1)));
        }

        runPass();

        assertThat(figures(busy)).containsEntry("trending_score", 17);
        assertThat(figures(saved)).containsEntry("trending_score", 21);
        assertThat(jdbc.queryForObject("SELECT computed_at FROM workflow_discovery_stats WHERE workflow_id = ?",
                Timestamp.class, busy).toInstant()).isEqualTo(NOW);
    }

    @Test
    void aWorkflowWithoutActivityGetsAZeroRowAndALaterPassUpdatesIt() {
        UUID w = catalogue(UUID.randomUUID());

        runPass();
        assertThat(figures(w)).containsEntry("runners_7d", 0).containsEntry("saves_7d", 0)
                .containsEntry("members_30d", 0).containsEntry("trending_score", 0);

        run(w, UUID.randomUUID(), "running", NOW.minus(Duration.ofHours(1)));
        runPass();

        assertThat(figures(w)).containsEntry("runners_7d", 1).containsEntry("trending_score", 3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_discovery_stats WHERE workflow_id = ?",
                Integer.class, w)).isEqualTo(1);
    }

    @Test
    void childRunsDoNotCount() {
        UUID chi = UUID.randomUUID();
        UUID p = catalogue(UUID.randomUUID());
        UUID c = catalogue(UUID.randomUUID());
        UUID parentRun = run(p, chi, "completed", NOW.minus(Duration.ofDays(1)));
        childRun(parentRun, c, chi, NOW.minus(Duration.ofDays(1)));

        runPass();

        assertThat(figures(p)).containsEntry("runners_7d", 1).containsEntry("members_30d", 1);
        assertThat(figures(c)).containsEntry("runners_7d", 0).containsEntry("members_30d", 0);
    }

    @Test
    void workflowThatLeftTheCatalogueLosesItsRow() {
        UUID owner = UUID.randomUUID();
        UUID madePrivate = catalogue(owner);
        UUID deleted = catalogue(owner);
        UUID unpublished = catalogue(owner);
        UUID stays = catalogue(owner);
        runPass();
        assertThat(rows(madePrivate, deleted, unpublished, stays)).isEqualTo(4);

        jdbc.update("UPDATE workflow SET visibility = 'private', is_shared = false WHERE id = ?", madePrivate);
        jdbc.update("UPDATE workflow SET deleted_at = now() WHERE id = ?", deleted);
        jdbc.update("UPDATE workflow SET status = 'draft' WHERE id = ?", unpublished);
        runPass();

        assertThat(rows(madePrivate, deleted, unpublished)).isZero();
        assertThat(rows(stays)).isEqualTo(1);
    }

    @Test
    void moreThanOneBatchOfWorkflowsAllGetARow() {
        jdbc.update("""
                INSERT INTO workflow (id, owner_id, name, is_shared, created_at, updated_at, status, visibility)
                SELECT gen_random_uuid(), gen_random_uuid(), 'dst-bulk-' || n, true, now(), now(), 'published', 'public'
                  FROM generate_series(1, 1200) AS n
                """);

        runPass();

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM workflow_discovery_stats s JOIN workflow w ON w.id = s.workflow_id
                 WHERE w.name LIKE 'dst-bulk-%'
                """, Integer.class)).isEqualTo(1200);
    }

    @Test
    void jobIsRegisteredUnderItsName() {
        ClusterJob job = registry.jobs().stream().filter(candidate -> JOB.equals(candidate.name())).findFirst()
                .orElseThrow();

        assertThat(job.interval()).isEqualTo(Duration.ofMillis(600_000));
        assertThat(job.lease()).isEqualTo(Duration.ofMinutes(10));
    }

    private void runPass() {
        registry.jobs().stream().filter(candidate -> JOB.equals(candidate.name())).findFirst().orElseThrow().run();
    }

    private UUID catalogue(UUID owner) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO workflow (id, owner_id, name, is_shared, created_at, updated_at, status, visibility)
                VALUES (?, ?, ?, true, ?, ?, 'published', 'public')
                """, id, owner, "dst-" + id, Timestamp.from(NOW), Timestamp.from(NOW));
        workflows.add(id);
        return id;
    }

    private UUID run(UUID workflow, UUID owner, String status, Instant startedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO workflow_run (id, workflow_id, owner_id, status, started_at) VALUES (?, ?, ?, ?, ?)",
                id, workflow, owner, status, Timestamp.from(startedAt));
        return id;
    }

    private void childRun(UUID parentRun, UUID workflow, UUID owner, Instant startedAt) {
        UUID step = UUID.randomUUID();
        jdbc.update("INSERT INTO run_step (id, run_id, step_order, type, status) VALUES (?, ?, 1, 'echo', 'running')",
                step, parentRun);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO workflow_run (id, workflow_id, owner_id, status, started_at, parent_run_id,
                                          parent_step_id, parent_step_attempt, call_depth)
                VALUES (?, ?, ?, 'completed', ?, ?, ?, 1, 1)
                """, id, workflow, owner, Timestamp.from(startedAt), parentRun, step);
        childRuns.add(id);
    }

    private void bookmark(UUID user, UUID workflow, Instant createdAt) {
        jdbc.update("INSERT INTO workflow_bookmark (user_id, workflow_id, created_at) VALUES (?, ?, ?)", user,
                workflow, Timestamp.from(createdAt));
    }

    private Map<String, Object> figures(UUID workflow) {
        return jdbc.queryForMap("""
                SELECT runners_7d, saves_7d, members_30d, trending_score FROM workflow_discovery_stats
                 WHERE workflow_id = ?
                """, workflow);
    }

    private int rows(UUID... ids) {
        int count = 0;
        for (UUID id : ids) {
            count += jdbc.queryForObject("SELECT count(*) FROM workflow_discovery_stats WHERE workflow_id = ?",
                    Integer.class, id);
        }
        return count;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
