package com.flowcortex.app.support.request;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.support.request.RequestAutoCloseJob;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The hourly job: a resolution closes 7 days after the member first saw it, never before they see it. */
@SpringBootTest(properties = {
        "flowcortex.support.enabled=true",
        "flowcortex.support.retention.interval=PT24H"})
class RequestAutoCloseJobIT extends AbstractPostgresBootTest {

    @Autowired
    private RequestAutoCloseJob job;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void emptyRequests() {
        jdbcTemplate.update("DELETE FROM support_request");
        jdbcTemplate.update("DELETE FROM support_idempotency");
    }

    @Test
    void aResolutionSeenSevenDaysAgoClosesWhileARecentOrUnseenOneStays() {
        Instant now = Instant.now();
        UUID seenLongAgo = resolved(now.minus(Duration.ofDays(8)), now.minus(Duration.ofDays(7)).minusSeconds(60));
        UUID seenRecently = resolved(now.minus(Duration.ofDays(6)), now.minus(Duration.ofDays(6)));
        UUID neverSeen = resolved(now.minus(Duration.ofDays(30)), null);

        job.run();

        assertThat(state(seenLongAgo)).containsEntry("status", "closed").containsEntry("closed_reason", "auto");
        assertThat(state(seenRecently)).containsEntry("status", "resolved");
        assertThat(state(neverSeen)).containsEntry("status", "resolved").containsEntry("member_unread", true);
    }

    @Test
    void aResolutionReResolvedAfterTheJobReadItStaysResolved() throws Exception {
        Instant now = Instant.now();
        UUID request = resolved(now.minus(Duration.ofDays(8)), now.minus(Duration.ofDays(7)).minusSeconds(60));
        AtomicReference<CompletableFuture<Void>> run = new AtomicReference<>();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbcTemplate.queryForObject("SELECT id FROM support_request WHERE id = ? FOR UPDATE", UUID.class, request);
            run.set(CompletableFuture.runAsync(job::run));
            awaitBlockedClose();
            // An admin re-resolves it: a new resolution the member has not seen yet.
            jdbcTemplate.update("UPDATE support_request SET resolved_at = now(), resolved_seen_at = NULL, "
                    + "member_unread = true WHERE id = ?", request);
        });
        run.get().get(10, TimeUnit.SECONDS);

        assertThat(state(request)).containsEntry("status", "resolved").containsEntry("member_unread", true);
    }

    @Test
    void idempotencyKeysOlderThanADayArePurged() {
        UUID requester = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO support_idempotency (requester_id, idem_key, kind, result_id, created_at) "
                + "VALUES (?, 'old', 'request', ?, ?), (?, 'fresh', 'request', ?, ?)",
                requester, UUID.randomUUID(), Timestamp.from(Instant.now().minus(Duration.ofHours(25))),
                requester, UUID.randomUUID(), Timestamp.from(Instant.now().minus(Duration.ofHours(23))));

        job.run();

        assertThat(jdbcTemplate.queryForList("SELECT idem_key FROM support_idempotency", String.class))
                .containsExactly("fresh");
    }

    private UUID resolved(Instant resolvedAt, Instant seenAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO support_request (id, requester_id, type, subject, status, member_unread, "
                + "last_activity_at, resolved_at, resolved_seen_at, created_at, updated_at) "
                + "VALUES (?, ?, 'credit', 'Không thấy credit', 'resolved', ?, ?, ?, ?, ?, ?)",
                id, UUID.randomUUID(), seenAt == null, Timestamp.from(resolvedAt), Timestamp.from(resolvedAt),
                seenAt == null ? null : Timestamp.from(seenAt), Timestamp.from(resolvedAt), Timestamp.from(resolvedAt));
        return id;
    }

    /** Waits until the job's close is blocked on the request row the test holds locked. */
    private void awaitBlockedClose() {
        Instant deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline)) {
            // Inside a transaction pg_stat_activity is a snapshot taken at first read.
            jdbcTemplate.queryForList("SELECT pg_stat_clear_snapshot()");
            Long blocked = jdbcTemplate.queryForObject("SELECT count(*) FROM pg_stat_activity "
                    + "WHERE wait_event_type = 'Lock' AND query LIKE 'UPDATE support_request%'", Long.class);
            if (blocked != null && blocked >= 1) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("The job's close never reached the row lock");
    }

    private Map<String, Object> state(UUID id) {
        return jdbcTemplate.queryForMap(
                "SELECT status, closed_reason, member_unread FROM support_request WHERE id = ?", id);
    }
}
