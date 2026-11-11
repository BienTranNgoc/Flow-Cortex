package com.flowcortex.app.asset;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.asset.domain.NewSpaceAsset;
import com.flowcortex.asset.domain.RegistrationRow;
import com.flowcortex.asset.domain.RegistrationStatus;
import com.flowcortex.asset.domain.SourceType;
import com.flowcortex.asset.infrastructure.AssetRegistrationStore;
import com.flowcortex.asset.infrastructure.SpaceAssetStore;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The registration queue's SQL and the asset table's uniqueness against a real Postgres. Rows are
 * committed (the claim race needs several connections), so each test starts and ends with both
 * tables empty.
 */
@Testcontainers
@SpringBootTest
class AssetRegistrationStoreIT extends AbstractPostgresBootTest {

    private static final Instant T0 = Instant.parse("2026-11-11T10:00:00Z");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private NamedParameterJdbcTemplate named;

    private MutableClock clock;
    private AssetRegistrationStore queue;
    private SpaceAssetStore assets;

    @BeforeEach
    void setUp() {
        clean();
        clock = new MutableClock(T0);
        queue = new AssetRegistrationStore(named, clock);
        assets = new SpaceAssetStore(named, clock);
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM space_asset_registration");
        jdbc.update("DELETE FROM space_asset");
    }

    @Test
    void upsertTwiceKeepsOneRow() {
        UUID run = UUID.randomUUID();
        UUID owner = UUID.randomUUID();

        queue.upsertPending(SourceType.RUN_OUTPUTS, run, owner);
        queue.upsertPending(SourceType.RUN_OUTPUTS, run, owner);
        queue.upsertPending(SourceType.RUN_INPUTS, run, owner);

        assertThat(count("space_asset_registration", "source_id = '" + run + "'")).isEqualTo(2);
        assertThat(count("space_asset_registration", "source_type = 'run_outputs' AND status = 'pending'"
                + " AND attempts = 0 AND next_attempt_at IS NOT NULL")).isEqualTo(1);
    }

    @Test
    void eightThreadsClaimingOneRowYieldOneClaim() throws Exception {
        UUID run = UUID.randomUUID();
        queue.upsertPending(SourceType.RUN_OUTPUTS, run, UUID.randomUUID());
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Optional<RegistrationRow>>> claims = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                claims.add(pool.submit(() -> {
                    start.await();
                    return queue.claim(SourceType.RUN_OUTPUTS, run);
                }));
            }
            start.countDown();
            int claimed = 0;
            for (Future<Optional<RegistrationRow>> claim : claims) {
                claimed += claim.get().isPresent() ? 1 : 0;
            }
            assertThat(claimed).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("space_asset_registration", "lease_token IS NOT NULL")).isEqualTo(1);
    }

    @Test
    void aLeasedRowIsNotClaimedUntilTheLeaseExpires() {
        UUID run = UUID.randomUUID();
        queue.upsertPending(SourceType.RUN_OUTPUTS, run, UUID.randomUUID());

        assertThat(queue.claim(SourceType.RUN_OUTPUTS, run)).isPresent();
        clock.set(T0.plus(Duration.ofMinutes(4)));
        assertThat(queue.claim(SourceType.RUN_OUTPUTS, run)).isEmpty();
        clock.set(T0.plus(Duration.ofMinutes(6)));
        assertThat(queue.claim(SourceType.RUN_OUTPUTS, run)).isPresent();
    }

    @Test
    void aFinishWithAStaleTokenChangesNothing() {
        UUID run = UUID.randomUUID();
        queue.upsertPending(SourceType.RUN_OUTPUTS, run, UUID.randomUUID());
        RegistrationRow first = queue.claim(SourceType.RUN_OUTPUTS, run).orElseThrow();
        clock.set(T0.plus(Duration.ofMinutes(6)));
        RegistrationRow second = queue.claim(SourceType.RUN_OUTPUTS, run).orElseThrow();

        assertThat(queue.finish(first, RegistrationStatus.DONE)).isFalse();
        assertThat(queue.retry(first, "store_unavailable")).isFalse();
        assertThat(count("space_asset_registration", "status = 'pending' AND attempts = 0"
                + " AND lease_token = '" + second.leaseToken() + "'")).isEqualTo(1);

        assertThat(queue.finish(second, RegistrationStatus.DONE)).isTrue();
        assertThat(count("space_asset_registration", "status = 'done' AND lease_token IS NULL"
                + " AND lease_until IS NULL")).isEqualTo(1);
    }

    @Test
    void retryRecordsCodeAttemptsAndNextAttempt() {
        UUID run = UUID.randomUUID();
        queue.upsertPending(SourceType.RUN_INPUTS, run, UUID.randomUUID());
        RegistrationRow claimed = queue.claim(SourceType.RUN_INPUTS, run).orElseThrow();

        assertThat(queue.retry(claimed, "store_unavailable")).isTrue();

        assertThat(count("space_asset_registration", "status = 'pending' AND attempts = 1"
                + " AND last_error_code = 'store_unavailable' AND lease_token IS NULL AND lease_until IS NULL"))
                .isEqualTo(1);
        Timestamp next = jdbc.queryForObject("SELECT next_attempt_at FROM space_asset_registration", Timestamp.class);
        assertThat(next.toInstant()).isEqualTo(T0.plus(Duration.ofMinutes(1)));
    }

    @Test
    void the288thFailureIsStuck() {
        UUID run = UUID.randomUUID();
        queue.upsertPending(SourceType.RUN_OUTPUTS, run, UUID.randomUUID());
        jdbc.update("UPDATE space_asset_registration SET attempts = 287");
        RegistrationRow claimed = queue.claim(SourceType.RUN_OUTPUTS, run).orElseThrow();

        assertThat(queue.retry(claimed, "insert_failed")).isTrue();

        assertThat(count("space_asset_registration", "status = 'stuck' AND attempts = 288"
                + " AND last_error_code = 'insert_failed' AND next_attempt_at IS NULL")).isEqualTo(1);
        clock.set(T0.plus(Duration.ofDays(1)));
        assertThat(queue.claimDue(50)).isEmpty();
    }

    @Test
    void claimDueTakesOnlyDuePendingRowsUpToTheLimit() {
        for (int i = 0; i < 60; i++) {
            queue.upsertPending(SourceType.RUN_OUTPUTS, UUID.randomUUID(), UUID.randomUUID());
        }
        clock.set(T0.plus(Duration.ofMinutes(1)));
        queue.upsertPending(SourceType.RUN_OUTPUTS, UUID.randomUUID(), UUID.randomUUID());
        jdbc.update("UPDATE space_asset_registration SET next_attempt_at = ? WHERE created_at > ?",
                Timestamp.from(T0.plus(Duration.ofHours(1))), Timestamp.from(T0));

        List<RegistrationRow> due = queue.claimDue(50);

        assertThat(due).hasSize(50);
        assertThat(due).allSatisfy(row -> assertThat(row.leaseToken()).isNotNull());
        assertThat(count("space_asset_registration", "lease_token IS NOT NULL")).isEqualTo(50);
        assertThat(queue.claimDue(50)).hasSize(10);
    }

    @Test
    void cleanupDeletesOnlyFinishedRowsOlderThan30Days() {
        UUID done = seedRow("done", T0.minus(Duration.ofDays(31)));
        UUID skipped = seedRow("skipped", T0.minus(Duration.ofDays(29)));
        UUID pending = seedRow("pending", T0.minus(Duration.ofDays(40)));
        UUID stuck = seedRow("stuck", T0.minus(Duration.ofDays(40)));

        assertThat(queue.cleanup(1000)).isEqualTo(1);

        assertThat(exists(done)).isFalse();
        assertThat(exists(skipped)).isTrue();
        assertThat(exists(pending)).isTrue();
        assertThat(exists(stuck)).isTrue();
    }

    @Test
    void insertIfAbsentIgnoresASecondAssetForTheSameMediaAsset() {
        UUID media = UUID.randomUUID();
        assertThat(assets.insertIfAbsent(asset(media, "b", "k1", UUID.randomUUID(), "First", "base", 1))).isTrue();
        assertThat(assets.insertIfAbsent(asset(media, "b", "k2", UUID.randomUUID(), "Second", "base", 1))).isFalse();

        assertThat(jdbc.queryForList("SELECT title FROM space_asset", String.class)).containsExactly("First");
    }

    @Test
    void insertIfAbsentIgnoresASecondAssetForTheSameObject() {
        assertThat(assets.insertIfAbsent(asset(UUID.randomUUID(), "b", "k", UUID.randomUUID(), "First", "base", 1)))
                .isTrue();
        assertThat(assets.insertIfAbsent(asset(UUID.randomUUID(), "b", "k", UUID.randomUUID(), "Second", "base", 1)))
                .isFalse();

        assertThat(jdbc.queryForList("SELECT title FROM space_asset", String.class)).containsExactly("First");
    }

    @Test
    void maxTitleVersionReadsOnlyRunAssetsOfTheSameSpaceAndBaseExcludingTheRun() {
        UUID space = UUID.randomUUID();
        UUID runA = UUID.randomUUID();
        UUID runB = UUID.randomUUID();
        assets.insertIfAbsent(inSpace(space, runA, "base", 2, "k1"));
        assets.insertIfAbsent(inSpace(space, runB, "base", 3, "k2"));
        assets.insertIfAbsent(inSpace(space, UUID.randomUUID(), "other base", 9, "k3"));
        assets.insertIfAbsent(inSpace(UUID.randomUUID(), UUID.randomUUID(), "base", 7, "k4"));
        assets.insertIfAbsent(upload(space, "base", 8, "k5"));

        assertThat(assets.maxRunTitleVersion(space, "base", UUID.randomUUID())).isEqualTo(3);
        assertThat(assets.maxRunTitleVersion(space, "base", runB)).isEqualTo(2);
        assertThat(assets.maxRunTitleVersion(space, "unused", runB)).isZero();
    }

    @Test
    void runTitleVersionReadsTheVersionOfAnAssetTheRunAlreadyWrote() {
        UUID run = UUID.randomUUID();
        assets.insertIfAbsent(inSpace(UUID.randomUUID(), run, "base", 4, "k1"));

        assertThat(assets.runTitleVersion(run)).hasValue(4);
        assertThat(assets.runTitleVersion(UUID.randomUUID())).isEmpty();
    }

    @Test
    void existsByMediaAssetIdSeesOnlyRegisteredUploads() {
        NewSpaceAsset registered = upload(UUID.randomUUID(), "base", 1, "k1");
        assets.insertIfAbsent(registered);

        assertThat(assets.existsByMediaAssetId(registered.mediaAssetId())).isTrue();
        assertThat(assets.existsByMediaAssetId(UUID.randomUUID())).isFalse();
    }

    private UUID seedRow(String status, Instant updatedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO space_asset_registration (id, source_type, source_id, owner_id, status, "
                + "created_at, updated_at) VALUES (?, 'run_outputs', ?, ?, ?, ?, ?)",
                id, UUID.randomUUID(), UUID.randomUUID(), status, Timestamp.from(updatedAt), Timestamp.from(updatedAt));
        return id;
    }

    private boolean exists(UUID rowId) {
        return count("space_asset_registration", "id = '" + rowId + "'") == 1;
    }

    private int count(String table, String where) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + where, Integer.class);
        return n == null ? 0 : n;
    }

    private static NewSpaceAsset asset(UUID media, String bucket, String key, UUID run, String title, String base,
            int version) {
        return new NewSpaceAsset(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), media, "run", "video",
                title, base, version, bucket, key, "video/mp4", 10L, run, UUID.randomUUID(), UUID.randomUUID(),
                "Workflow");
    }

    private static NewSpaceAsset inSpace(UUID space, UUID run, String base, int version, String key) {
        return new NewSpaceAsset(UUID.randomUUID(), space, UUID.randomUUID(), UUID.randomUUID(), "run", "video",
                base, base, version, "media", key, "video/mp4", 10L, run, UUID.randomUUID(), UUID.randomUUID(),
                "Workflow");
    }

    private static NewSpaceAsset upload(UUID space, String base, int version, String key) {
        return new NewSpaceAsset(UUID.randomUUID(), space, UUID.randomUUID(), UUID.randomUUID(), "upload", "image",
                base, base, version, "media", key, "image/png", 10L, UUID.randomUUID(), null, null, null);
    }

    /** A clock the test moves; the stores read it for every timestamp they write or compare. */
    private static final class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            this.now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
