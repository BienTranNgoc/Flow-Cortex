package com.flowcortex.app.asset;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.asset.application.AssetRegistrationSweep;
import com.flowcortex.asset.domain.SourceType;
import com.flowcortex.asset.infrastructure.AssetRegistrationStore;
import com.flowcortex.space.api.PersonalSpaces;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The sweep against a real Postgres, called directly because the cluster runner is off in the suite. Every
 * test starts from an empty queue: a pass claims whichever due rows the table holds, so rows left by other
 * classes of the shared database would otherwise decide what it takes.
 */
@Testcontainers
@SpringBootTest
class AssetRegistrationSweepIT extends AbstractPostgresBootTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AssetRegistrationSweep sweep;

    @Autowired
    private AssetRegistrationStore queue;

    @Autowired
    private PersonalSpaces spaces;

    private final List<UUID> owners = new ArrayList<>();

    @BeforeEach
    void emptyTheQueue() {
        jdbc.update("DELETE FROM space_asset_registration");
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM space_asset_registration");
        for (UUID owner : owners) {
            jdbc.update("DELETE FROM space_asset WHERE owner_id = ?", owner);
            jdbc.update("DELETE FROM media_asset WHERE owner_id = ?", owner);
            jdbc.update("DELETE FROM run_step WHERE run_id IN (SELECT id FROM workflow_run WHERE owner_id = ?)", owner);
            jdbc.update("DELETE FROM workflow_run WHERE owner_id = ?", owner);
            jdbc.update("DELETE FROM workflow WHERE owner_id = ?", owner);
        }
        owners.clear();
    }

    @Test
    void aDuePendingOutputsRowIsRegisteredByTheNextPass() {
        UUID owner = newOwner();
        UUID run = completedRunWithVideo(owner);
        queue.upsertPending(SourceType.RUN_OUTPUTS, run, owner);

        sweep.run();

        assertThat(jdbc.queryForList("SELECT title FROM space_asset WHERE run_id = ?", String.class, run))
                .containsExactly("sweep input · Dub");
        assertThat(rowStatus(run)).isEqualTo("done");
    }

    @Test
    void aRowNotYetDueIsLeftAlone() {
        UUID owner = newOwner();
        UUID run = completedRunWithVideo(owner);
        queue.upsertPending(SourceType.RUN_OUTPUTS, run, owner);
        jdbc.update("UPDATE space_asset_registration SET next_attempt_at = ? WHERE source_id = ?",
                Timestamp.from(Instant.now().plus(Duration.ofHours(1))), run);

        sweep.run();

        assertThat(jdbc.queryForList("SELECT title FROM space_asset WHERE run_id = ?", String.class, run)).isEmpty();
        assertThat(rowStatus(run)).isEqualTo("pending");
        assertThat(jdbc.queryForObject("SELECT lease_token FROM space_asset_registration WHERE source_id = ?",
                UUID.class, run)).isNull();
    }

    @Test
    void aPassClaimsAtMostFiftyRows() {
        UUID owner = UUID.randomUUID();
        for (int i = 0; i < 60; i++) {
            queue.upsertPending(SourceType.RUN_OUTPUTS, UUID.randomUUID(), owner);
        }

        sweep.run();

        assertThat(count("status = 'skipped'")).isEqualTo(50);
        assertThat(count("status = 'pending'")).isEqualTo(10);

        sweep.run();

        assertThat(count("status = 'skipped'")).isEqualTo(60);
    }

    @Test
    void aPassDeletesFinishedRowsOlderThan30DaysAndKeepsPendingOnes() {
        UUID owner = UUID.randomUUID();
        UUID oldDone = queueRow(owner, "done", 31, null);
        UUID oldSkipped = queueRow(owner, "skipped", 31, null);
        UUID recentDone = queueRow(owner, "done", 29, null);
        UUID oldPending = queueRow(owner, "pending", 40, Instant.now().plus(Duration.ofHours(1)));
        UUID oldStuck = queueRow(owner, "stuck", 40, null);

        sweep.run();

        assertThat(jdbc.queryForList("SELECT source_id FROM space_asset_registration", UUID.class))
                .containsExactlyInAnyOrder(recentDone, oldPending, oldStuck)
                .doesNotContain(oldDone, oldSkipped);
    }

    @Test
    void jobIsNamedAssetRegistrationSweepWithTheConfiguredIntervalDefault120Seconds() {
        assertThat(sweep.name()).isEqualTo("asset.registration-sweep");
        assertThat(sweep.interval()).isEqualTo(Duration.ofSeconds(120));
    }

    private UUID queueRow(UUID owner, String status, int ageDays, Instant nextAttemptAt) {
        UUID source = UUID.randomUUID();
        Timestamp updated = Timestamp.from(Instant.now().minus(Duration.ofDays(ageDays)));
        jdbc.update("INSERT INTO space_asset_registration (id, source_type, source_id, owner_id, status, attempts, "
                + "next_attempt_at, created_at, updated_at) VALUES (?, 'run_outputs', ?, ?, ?, 0, ?, ?, ?)",
                UUID.randomUUID(), source, owner, status,
                nextAttemptAt == null ? null : Timestamp.from(nextAttemptAt), updated, updated);
        return source;
    }

    private int count(String where) {
        return jdbc.queryForObject("SELECT count(*) FROM space_asset_registration WHERE " + where, Integer.class);
    }

    private String rowStatus(UUID run) {
        return jdbc.queryForObject("SELECT status FROM space_asset_registration WHERE source_type = 'run_outputs' "
                + "AND source_id = ?", String.class, run);
    }

    private UUID newOwner() {
        UUID owner = UUID.randomUUID();
        owners.add(owner);
        spaces.personalSpaceOf(owner);
        return owner;
    }

    private UUID completedRunWithVideo(UUID owner) {
        UUID workflow = UUID.randomUUID();
        jdbc.update("INSERT INTO workflow (id, owner_id, name, is_shared, created_at, updated_at) "
                + "VALUES (?, ?, 'Dub', false, now(), now())", workflow, owner);
        UUID run = UUID.randomUUID();
        jdbc.update("INSERT INTO workflow_run (id, workflow_id, owner_id, status, input, started_at, finished_at) "
                + "VALUES (?, ?, ?, 'completed', 'sweep input', now(), now())", run, workflow, owner);
        UUID step = UUID.randomUUID();
        jdbc.update("INSERT INTO run_step (id, run_id, step_order, type, status) VALUES (?, ?, 1, 'burn', 'completed')",
                step, run);
        jdbc.update("INSERT INTO media_asset (id, owner_id, space_id, kind, bucket, object_key, mime_type, bytes, "
                + "status, source_run_step_id, source_attempt, created_at) "
                + "VALUES (?, ?, ?, 'video', ?, ?, 'video/mp4', 100, 'ready', ?, 1, now())",
                UUID.randomUUID(), owner, spaces.personalSpaceOf(owner), MEDIA_BUCKET,
                "runs/" + run + "/steps/1/out.mp4", step);
        return run;
    }
}
