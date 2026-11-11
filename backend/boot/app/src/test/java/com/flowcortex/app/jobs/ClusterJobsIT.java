package com.flowcortex.app.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.jobs.api.ClusterJob;
import com.flowcortex.jobs.application.ClusterJobRegistry;
import com.flowcortex.jobs.application.ClusterJobRunner;
import com.flowcortex.jobs.infrastructure.ScheduledJobRepository;
import com.flowcortex.jobs.infrastructure.ScheduledJobRepository.Outcome;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.NestedTestConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The schedule table and the runner against real Postgres. The runner bean is off in the suite (so cached
 * contexts do not run each other's jobs): the tests build their own runner over test-only {@code test.*}
 * jobs and a clock they move by hand. Two nested contexts lock the switch itself.
 */
@Testcontainers
@SpringBootTest
class ClusterJobsIT extends AbstractPostgresBootTest {

    private static final Duration HOUR = Duration.ofHours(1);
    private static final Duration TEN_MINUTES = Duration.ofMinutes(10);

    @Autowired
    private ScheduledJobRepository schedule;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM scheduled_job WHERE name LIKE 'test.%' OR name = 'legacy.cleanup'");
    }

    private static Instant at(String time) {
        return Instant.parse("2026-11-10T" + time + ":00Z");
    }

    private Map<String, Object> row(String name) {
        return jdbc.queryForMap("SELECT * FROM scheduled_job WHERE name = ?", name);
    }

    private Instant nextRun(String name) {
        return ((Timestamp) row(name).get("next_run_at")).toInstant();
    }

    private boolean claim(String name, UUID token, Instant now) {
        return schedule.claim(name, token, "owner/test", now, TEN_MINUTES);
    }

    private void claimAndFinish(String name, Instant start, Instant end) {
        UUID token = UUID.randomUUID();
        assertThat(claim(name, token, start)).isTrue();
        assertThat(schedule.finish(name, token, end, Outcome.SUCCEEDED, null)).isTrue();
    }

    @Test
    void eightThreadsClaimingOneDueRowGetExactlyOneClaim() throws Exception {
        schedule.register("test.concurrent", HOUR, at("10:00"));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return claim("test.concurrent", UUID.randomUUID(), at("10:00"));
            }));
        }
        go.countDown();
        int claims = 0;
        for (Future<Boolean> result : results) {
            if (result.get(30, TimeUnit.SECONDS)) {
                claims++;
            }
        }
        pool.shutdownNow();

        assertThat(claims).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(lease_token) FROM scheduled_job WHERE name = 'test.concurrent'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void aRowNotYetDueIsNotClaimed() {
        schedule.register("test.waiting", HOUR, at("10:00"));
        claimAndFinish("test.waiting", at("10:00"), at("10:00"));

        assertThat(claim("test.waiting", UUID.randomUUID(), at("10:30"))).isFalse();
        assertThat(claim("test.waiting", UUID.randomUUID(), at("11:00"))).isTrue();
    }

    @Test
    void aClaimHoldsForItsLeaseAndThenAnotherInstanceTakesOver() {
        schedule.register("test.lease", HOUR, at("10:00"));
        assertThat(claim("test.lease", UUID.randomUUID(), at("10:00"))).isTrue();

        assertThat(claim("test.lease", UUID.randomUUID(), at("10:05"))).isFalse();
        assertThat(claim("test.lease", UUID.randomUUID(), at("10:11"))).isTrue();
    }

    @Test
    void theFirstHoldersFinishAfterTheTakeoverChangesNothing() {
        schedule.register("test.takeover", HOUR, at("10:00"));
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        assertThat(claim("test.takeover", first, at("10:00"))).isTrue();
        assertThat(claim("test.takeover", second, at("10:11"))).isTrue();

        assertThat(schedule.finish("test.takeover", first, at("10:12"), Outcome.SUCCEEDED, null)).isFalse();

        Map<String, Object> held = row("test.takeover");
        assertThat(held.get("lease_token")).isEqualTo(second);
        assertThat(held.get("last_outcome")).isNull();
        assertThat(nextRun("test.takeover")).isEqualTo(at("10:00"));
        assertThat(schedule.finish("test.takeover", second, at("10:13"), Outcome.SUCCEEDED, null)).isTrue();
        assertThat(nextRun("test.takeover")).isEqualTo(at("11:13"));
    }

    @Test
    void registeringAgainKeepsTheNextRunAndANewJobIsDueAtOnce() {
        schedule.register("test.restart", HOUR, at("10:00"));
        assertThat(nextRun("test.restart")).isEqualTo(at("10:00"));
        claimAndFinish("test.restart", at("10:00"), at("10:00"));
        assertThat(nextRun("test.restart")).isEqualTo(at("11:00"));

        schedule.register("test.restart", HOUR, at("10:10"));

        assertThat(nextRun("test.restart")).isEqualTo(at("11:00"));
    }

    @Test
    void aShortenedIntervalBringsTheNextRunForward() {
        schedule.register("test.shortened", HOUR, at("10:00"));
        claimAndFinish("test.shortened", at("10:00"), at("10:00"));
        assertThat(nextRun("test.shortened")).isEqualTo(at("11:00"));

        schedule.register("test.shortened", TEN_MINUTES, at("10:05"));

        assertThat(row("test.shortened").get("interval_ms")).isEqualTo(600_000L);
        assertThat(nextRun("test.shortened")).isEqualTo(at("10:15"));
    }

    @Test
    void aThrowingJobRecordsFailedWhileTheNextDueJobStillRuns() {
        AtomicInteger ran = new AtomicInteger();
        ClusterJob thrower = job("test.a-thrower", HOUR, () -> {
            throw new IllegalStateException("secret detail that must not be stored");
        });
        ClusterJob healthy = job("test.b-healthy", HOUR, ran::incrementAndGet);
        ClusterJobRunner runner = runner(at("10:00"), thrower, healthy);

        runner.register();
        runner.runPass();

        Map<String, Object> failed = row("test.a-thrower");
        assertThat(failed.get("last_outcome")).isEqualTo("failed");
        assertThat(failed.get("last_error")).isEqualTo("IllegalStateException");
        assertThat(nextRun("test.a-thrower")).isEqualTo(at("11:00"));
        assertThat(row("test.b-healthy").get("last_outcome")).isEqualTo("succeeded");
        assertThat(ran).hasValue(1);
    }

    @Test
    void aRowNoBeanRegistersIsNeitherRunNorChanged() {
        jdbc.update("INSERT INTO scheduled_job (name, interval_ms, next_run_at, created_at, updated_at) "
                + "VALUES ('legacy.cleanup', 3600000, ?, ?, ?)", Timestamp.from(at("09:00")),
                Timestamp.from(at("08:00")), Timestamp.from(at("08:00")));
        Map<String, Object> before = row("legacy.cleanup");
        ClusterJobRunner runner = runner(at("10:00"), job("test.other", HOUR, () -> { }));

        runner.register();
        runner.runPass();

        assertThat(row("legacy.cleanup")).isEqualTo(before);
    }

    @Test
    void shuttingDownReleasesTheClaimKeepingTheNextRunAndTheLateFinishChangesNothing() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        ClusterJob blocking = job("test.shutdown", HOUR, () -> {
            started.countDown();
            awaitLatch(finish);
        });
        AtomicInteger secondRan = new AtomicInteger();
        ClusterJobRunner first = runner(at("10:00"), blocking);
        ClusterJobRunner second = runner(at("10:00"), job("test.shutdown", HOUR, secondRan::incrementAndGet));
        first.register();
        second.register();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<?> firstPass = pool.submit(first::runPass);
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();
        first.release();
        assertThat(row("test.shutdown").get("lease_token")).isNull();
        assertThat(nextRun("test.shutdown")).isEqualTo(at("10:00"));

        second.runPass();
        assertThat(secondRan).hasValue(1);
        Map<String, Object> afterSecond = row("test.shutdown");

        finish.countDown();
        firstPass.get(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(row("test.shutdown")).isEqualTo(afterSecond);
    }

    @Test
    void aNewJobRunsInTheFirstPassAfterRegistrationAndNotInOneBeforeIt() {
        AtomicInteger ran = new AtomicInteger();
        ClusterJobRunner runner = runner(at("10:00"), job("test.first-pass", HOUR, ran::incrementAndGet));

        runner.runPass();
        assertThat(ran).hasValue(0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM scheduled_job WHERE name = 'test.first-pass'",
                Integer.class)).isZero();

        runner.register();
        runner.runPass();

        assertThat(ran).hasValue(1);
        assertThat(row("test.first-pass").get("last_outcome")).isEqualTo("succeeded");
    }

    private ClusterJobRunner runner(Instant now, ClusterJob... jobs) {
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        return new ClusterJobRunner(new ClusterJobRegistry(List.of(jobs)), schedule, clock);
    }

    private static ClusterJob job(String name, Duration interval, Runnable body) {
        return new ClusterJob() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Duration interval() {
                return interval;
            }

            @Override
            public void run() {
                body.run();
            }
        };
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The suite default: no runner bean, so nothing registers or runs a job. */
    @Nested
    class RunnerOff {

        @Test
        void aDeletedResubmitterRowStaysAbsent() throws Exception {
            jdbc.update("DELETE FROM scheduled_job WHERE name = 'jobs.publication-resubmitter'");

            Thread.sleep(10_000);

            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM scheduled_job WHERE name = 'jobs.publication-resubmitter'", Integer.class))
                    .isZero();
        }

        @Test
        void noRunnerBeanExists(@Autowired ApplicationContext context) {
            assertThat(context.getBeanNamesForType(ClusterJobRunner.class)).isEmpty();
        }
    }

    /** The switch on: the runner registers every job bean with its configured interval. */
    @Nested
    @NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.OVERRIDE)
    @DirtiesContext
    @SpringBootTest(properties = "flowcortex.jobs.runner.enabled=true")
    class RunnerOn extends AbstractPostgresBootTest {

        @Autowired
        private JdbcTemplate rows;

        @Test
        void theEightJobsHaveRowsWithTheirConfiguredIntervals() {
            Map<String, Long> expected = Map.of(
                    "jobs.publication-resubmitter", 60_000L,
                    "workflow.lease-reaper", 30_000L,
                    "user.auth-retention", 3_600_000L,
                    "user.unconfirmed-account-purge", 3_600_000L,
                    "user.phone-retention", 3_600_000L,
                    "support.assistant-retention", 3_600_000L,
                    "support.request-auto-close", 3_600_000L,
                    "asset.registration-sweep", 120_000L);

            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                Map<String, Long> stored = rows.queryForList(
                        "SELECT name, interval_ms FROM scheduled_job WHERE name NOT LIKE 'test.%'").stream()
                        .collect(Collectors.toMap(r -> (String) r.get("name"), r -> (Long) r.get("interval_ms")));
                assertThat(stored).containsExactlyInAnyOrderEntriesOf(expected);
            });
        }
    }
}
