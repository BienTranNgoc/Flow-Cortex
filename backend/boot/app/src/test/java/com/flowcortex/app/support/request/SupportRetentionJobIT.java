package com.flowcortex.app.support.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.jobs.infrastructure.FailedPublicationResubmitter;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.media.api.StoredAttachment;
import com.flowcortex.settings.api.Settings;
import com.flowcortex.support.config.SupportLifecycleSettings;
import com.flowcortex.support.request.SupportRetentionJob;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * The retention job: a request closed longer ago than the configured months is deleted with its messages and
 * attachment rows, and its images are released after the delete commits, retried until the store takes them.
 */
@SpringBootTest(properties = {
        "flowcortex.support.enabled=true",
        "flowcortex.support.request-retention.interval=PT24H",
        "flowcortex.support.retention.interval=PT24H"})
@ExtendWith(OutputCaptureExtension.class)
class SupportRetentionJobIT extends AbstractPostgresBootTest {

    private static final String RELEASE = "support.attachments-release";

    @Autowired
    private SupportRetentionJob job;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Settings settings;

    @Autowired
    private FailedPublicationResubmitter resubmitter;

    @MockitoSpyBean
    private MediaJobs mediaJobs;

    /** Object keys the store accepted, across every release call. */
    private final List<String> released = new CopyOnWriteArrayList<>();

    @BeforeEach
    void cleanSlate() {
        reset(mediaJobs);
        released.clear();
        jdbcTemplate.update("DELETE FROM support_request");
        jdbcTemplate.update("DELETE FROM app_setting WHERE key LIKE 'support.retention.%'");
        jdbcTemplate.update("DELETE FROM app_setting_change WHERE key LIKE 'support.retention.%'");
        jdbcTemplate.update("DELETE FROM event_publication WHERE listener_id = ?", RELEASE);
    }

    @Test
    void aClosedRequestPastTheRetentionIsDeletedAndItsImagesReleasedWhileAnOpenOneStays(CapturedOutput output) {
        storeTakesReleases(0);
        UUID closed = request("closed", monthsAgo(25));
        addImages(closed, 2);
        UUID waiting = request("waiting_member", monthsAgo(30));
        addImages(waiting, 1);

        job.run();

        assertThat(jdbcTemplate.queryForList("SELECT id FROM support_request", UUID.class)).containsExactly(waiting);
        assertThat(count("support_message WHERE request_id = ?", closed)).isZero();
        assertThat(count("support_attachment WHERE message_id IN (SELECT id FROM support_message "
                + "WHERE request_id = ?)", closed)).isZero();
        assertThat(count("support_message WHERE request_id = ?", waiting)).isEqualTo(1);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(released)
                .containsExactlyInAnyOrder(imageKey(closed, 1), imageKey(closed, 2)));
        assertThat(output.getAll()).contains("support.request.retention deleted=1 released=2");
    }

    @Test
    void aClosedRequestInsideTheRetentionStays() {
        UUID recent = request("closed", monthsAgo(23));

        job.run();

        assertThat(jdbcTemplate.queryForList("SELECT id FROM support_request", UUID.class)).containsExactly(recent);
    }

    @Test
    void aShorterRetentionSetByAnAdminDeletesEarlier() {
        storeTakesReleases(0);
        settings.set(SupportLifecycleSettings.RETENTION_CLOSED_REQUEST_MONTHS, 6, UUID.randomUUID());
        request("closed", monthsAgo(7));
        UUID fiveMonths = request("closed", monthsAgo(5));

        job.run();

        assertThat(jdbcTemplate.queryForList("SELECT id FROM support_request", UUID.class))
                .containsExactly(fiveMonths);
    }

    @Test
    void withRetentionSwitchedOffNothingIsDeleted(CapturedOutput output) {
        settings.set(SupportLifecycleSettings.RETENTION_ENABLED, false, UUID.randomUUID());
        UUID old = request("closed", monthsAgo(30));
        addImages(old, 1);

        job.run();

        assertThat(jdbcTemplate.queryForList("SELECT id FROM support_request", UUID.class)).containsExactly(old);
        assertThat(output.getAll()).contains("support.request.retention skipped=disabled");
    }

    @Test
    void anImageReleaseThatFailsOnceIsRetriedAndTheRequestIsGoneAfterTheFirstRun() {
        storeTakesReleases(1);
        UUID closed = request("closed", monthsAgo(25));
        addImages(closed, 2);

        job.run();

        assertThat(count("support_request")).isZero();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(jdbcTemplate.queryForList(
                "SELECT completion_attempts FROM event_publication WHERE listener_id = ? AND status = 'FAILED'",
                Integer.class, RELEASE)).containsExactly(1));
        assertThat(released).isEmpty();

        Timestamp due = Timestamp.from(Instant.now().minus(Duration.ofMinutes(2)));
        jdbcTemplate.update("UPDATE event_publication SET publication_date = ?, last_resubmission_date = "
                + "CASE WHEN last_resubmission_date IS NULL THEN NULL ELSE CAST(? AS timestamptz) END "
                + "WHERE listener_id = ?", due, due, RELEASE);
        resubmitter.run();

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(released)
                .containsExactlyInAnyOrder(imageKey(closed, 1), imageKey(closed, 2)));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM event_publication WHERE listener_id = ?", Long.class, RELEASE)).isZero());
    }

    @Test
    void fourHundredFiftyQualifyingRequestsAreDeletedOverOneRunInBatchesOfTwoHundred() {
        Instant old = monthsAgo(26);
        jdbcTemplate.update("INSERT INTO support_request (id, requester_id, type, subject, status, member_unread, "
                + "last_activity_at, closed_reason, created_at, updated_at) "
                + "SELECT gen_random_uuid(), gen_random_uuid(), 'other', 'Old closed request', 'closed', false, "
                + "?, 'admin', ?, ? FROM generate_series(1, 450)", Timestamp.from(old), Timestamp.from(old),
                Timestamp.from(old));
        UUID open = request("resolved", monthsAgo(40));

        job.run();

        assertThat(jdbcTemplate.queryForList("SELECT id FROM support_request", UUID.class)).containsExactly(open);
    }

    /** The store refuses the first {@code failures} release calls, then takes every later one. */
    private void storeTakesReleases(int failures) {
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.incrementAndGet() <= failures) {
                throw new IllegalStateException("store down");
            }
            List<StoredAttachment> attachments = invocation.getArgument(0);
            attachments.forEach(attachment -> released.add(attachment.objectKey()));
            return null;
        }).when(mediaJobs).releaseAttachments(anyList());
    }

    private long count(String fromAndWhere, Object... args) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + fromAndWhere, Long.class, args);
    }

    private static Instant monthsAgo(int months) {
        return Instant.now().atOffset(ZoneOffset.UTC).minusMonths(months).toInstant();
    }

    private UUID request(String status, Instant lastActivity) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO support_request (id, requester_id, type, subject, status, member_unread, "
                + "last_activity_at, closed_reason, created_at, updated_at) "
                + "VALUES (?, ?, 'credit', 'Không thấy credit', ?, false, ?, ?, ?, ?)",
                id, UUID.randomUUID(), status, Timestamp.from(lastActivity),
                "closed".equals(status) ? "admin" : null, Timestamp.from(lastActivity), Timestamp.from(lastActivity));
        jdbcTemplate.update("INSERT INTO support_message (id, request_id, author_kind, author_id, body, created_at) "
                + "VALUES (?, ?, 'member', ?, 'Mô tả', ?)", UUID.randomUUID(), id, UUID.randomUUID(),
                Timestamp.from(lastActivity));
        return id;
    }

    private void addImages(UUID requestId, int count) {
        UUID message = jdbcTemplate.queryForObject("SELECT id FROM support_message WHERE request_id = ?",
                UUID.class, requestId);
        for (int position = 1; position <= count; position++) {
            jdbcTemplate.update("INSERT INTO support_attachment (id, message_id, bucket, object_key, content_type, "
                    + "size_bytes, position, created_at) VALUES (?, ?, ?, ?, 'image/png', 1024, ?, now())",
                    UUID.randomUUID(), message, MEDIA_BUCKET, imageKey(requestId, position), position);
        }
    }

    private static String imageKey(UUID requestId, int position) {
        return "support/" + requestId + "/message/" + position;
    }
}
