package com.flowcortex.app.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.jobs.api.PublicationRetryPolicy;
import com.flowcortex.jobs.infrastructure.FailedPublicationResubmitter;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The resubmitter against real publication rows: given-up rows leave the table with one log line each,
 * rows in back-off and given-up rows never hide a due one, and a pass resubmits at most a hundred.
 */
@Testcontainers
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
@Import(PublicationResubmitterIT.Probes.class)
class PublicationResubmitterIT extends AbstractPostgresBootTest {

    private static final List<String> RECEIVED = new CopyOnWriteArrayList<>();

    /** Retried with the default policy: ten attempts, a minute apart. */
    public record DefaultProbe(String marker) {
    }

    /** Retried with an hour between attempts, so a row a few minutes old is still waiting. */
    public record SlowProbe(String marker) {
    }

    @TestConfiguration
    static class Probes {

        @Bean
        PublicationRetryPolicy slowProbePolicy() {
            return new PublicationRetryPolicy() {
                @Override
                public Class<?> eventType() {
                    return SlowProbe.class;
                }

                @Override
                public int maxAttempts() {
                    return 5;
                }

                @Override
                public Duration delayAfter(int attempts) {
                    return Duration.ofHours(1);
                }
            };
        }

        @Bean
        Listeners probeListeners() {
            return new Listeners();
        }
    }

    static class Listeners {

        @ApplicationModuleListener(id = "probe.default")
        void on(DefaultProbe event) {
            RECEIVED.add(event.marker());
        }

        @ApplicationModuleListener(id = "probe.slow")
        void on(SlowProbe event) {
            RECEIVED.add(event.marker());
        }
    }

    @Autowired
    private FailedPublicationResubmitter resubmitter;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM event_publication WHERE listener_id LIKE 'probe.%'");
        RECEIVED.clear();
    }

    private void failed(String listener, Class<?> type, String marker, int attempts, Duration age) {
        jdbc.update("INSERT INTO event_publication (id, listener_id, event_type, serialized_event, "
                + "publication_date, status, completion_attempts) VALUES (?, ?, ?, ?, ?, 'FAILED', ?)",
                UUID.randomUUID(), listener, type.getName(), "{\"marker\":\"" + marker + "\"}",
                Timestamp.from(Instant.now().minus(age)), attempts);
    }

    private int stored() {
        return jdbc.queryForObject("SELECT count(*) FROM event_publication WHERE listener_id LIKE 'probe.%'",
                Integer.class);
    }

    private static int giveUps(CapturedOutput output) {
        return (int) output.getAll().lines().filter(line -> line.contains("outcome=gave_up")).count();
    }

    @Test
    void givenUpRowsDoNotHideADueOneAndEachIsLoggedOnceBeforeItIsDeleted(CapturedOutput output) {
        IntStream.range(0, 150).forEach(i -> failed("probe.default", DefaultProbe.class, "exhausted-" + i, 10,
                Duration.ofMinutes(10)));
        failed("probe.slow", SlowProbe.class, "due", 1, Duration.ofHours(2));

        resubmitter.run();
        resubmitter.run();

        await().atMost(Duration.ofSeconds(20)).until(() -> RECEIVED.contains("due"));
        await().atMost(Duration.ofSeconds(20)).until(() -> stored() == 0);
        assertThat(RECEIVED).containsExactly("due");
        assertThat(giveUps(output)).isEqualTo(150);
    }

    @Test
    void aDefaultPolicyRowWithNineAttemptsFromTwoMinutesAgoIsResubmitted() {
        failed("probe.default", DefaultProbe.class, "nine", 9, Duration.ofMinutes(2));

        resubmitter.run();

        await().atMost(Duration.ofSeconds(20)).until(() -> RECEIVED.contains("nine"));
    }

    @Test
    void waitingRowsDoNotHideADueOne() {
        IntStream.range(0, 150).forEach(i -> failed("probe.slow", SlowProbe.class, "waiting-" + i, 1,
                Duration.ofMinutes(10)));
        failed("probe.slow", SlowProbe.class, "due", 1, Duration.ofHours(2));

        resubmitter.run();
        resubmitter.run();

        await().atMost(Duration.ofSeconds(20)).until(() -> RECEIVED.contains("due"));
        assertThat(RECEIVED).containsExactly("due");
        assertThat(stored()).isEqualTo(150);
    }

    @Test
    void aPassResubmitsAtMostAHundredDueRows() throws Exception {
        IntStream.range(0, 150).forEach(i -> failed("probe.slow", SlowProbe.class, "due-" + i, 1,
                Duration.ofHours(2)));

        resubmitter.run();

        await().atMost(Duration.ofSeconds(30)).until(() -> RECEIVED.size() >= 100);
        Thread.sleep(2_000);
        assertThat(RECEIVED).hasSize(100);
        assertThat(stored()).isEqualTo(50);
    }
}
