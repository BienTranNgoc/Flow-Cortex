package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flowcortex.media.api.MediaAssetInput;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.media.api.StoredAttachment;
import com.flowcortex.media.api.UploadPurpose;
import com.flowcortex.user.api.UserDirectory;
import com.flowcortex.workflow.infrastructure.WorkflowRatingTotals;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import com.flowcortex.workflow.infrastructure.WorkflowReviewHelpfulRepository;
import com.flowcortex.workflow.infrastructure.WorkflowReviewImageRepository;
import com.flowcortex.workflow.infrastructure.WorkflowReviewReportRepository;
import com.flowcortex.workflow.infrastructure.WorkflowReviewRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The rating totals kept by review writes, and the fold function and backfill of the discovery migration,
 * against a real Postgres on the Flyway-migrated schema. Nothing runs in a test transaction: each write must
 * really commit or roll back.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.flyway.locations=filesystem:${flowcortex.migrations.dir}",
        "spring.flyway.postgresql.transactional-lock=false",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@Import(WorkflowRatingTotals.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class WorkflowRatingTotalsIT {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    private static final String MIGRATION = "V2026.11.24.052__workflow_rating_total_backfill.sql";

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private WorkflowRunRepository runRepository;

    @Autowired
    private WorkflowReviewRepository reviewRepository;

    @Autowired
    private WorkflowReviewImageRepository imageRepository;

    @Autowired
    private WorkflowReviewHelpfulRepository helpfulRepository;

    @Autowired
    private WorkflowReviewReportRepository reportRepository;

    @Autowired
    private WorkflowRatingTotals ratingTotals;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Value("${flowcortex.migrations.dir}")
    private String migrationsDir;

    private final MediaJobs mediaJobs = mock(MediaJobs.class);

    private WorkflowReviewService service;

    @BeforeEach
    void buildService() {
        when(mediaJobs.isEnabled()).thenReturn(true);
        service = new WorkflowReviewService(workflowRepository, runRepository, reviewRepository, imageRepository,
                helpfulRepository, reportRepository, mediaJobs, mock(UserDirectory.class), ratingTotals,
                transactionManager, Clock.systemUTC());
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    private UUID publicWorkflow() {
        UUID id = UUID.randomUUID();
        Timestamp at = Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS));
        jdbc().update("INSERT INTO workflow (id, owner_id, name, is_shared, created_at, updated_at, status,"
                        + " visibility) VALUES (?, ?, ?, true, ?, ?, 'published', 'public')",
                id, UUID.randomUUID(), "it-" + id, at, at);
        return id;
    }

    private UUID memberWithCompletedRun(UUID workflow) {
        UUID member = UUID.randomUUID();
        jdbc().update("INSERT INTO workflow_run (id, workflow_id, owner_id, status, started_at)"
                + " VALUES (?, ?, ?, 'completed', now())", UUID.randomUUID(), workflow, member);
        return member;
    }

    private void insertReview(UUID workflow, int rating) {
        Timestamp at = Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS));
        jdbc().update("INSERT INTO workflow_review (id, workflow_id, user_id, rating, body, helpful_count,"
                        + " created_at, updated_at) VALUES (?, ?, ?, ?, NULL, 0, ?, ?)",
                UUID.randomUUID(), workflow, UUID.randomUUID(), rating, at, at);
    }

    private Map<String, Object> totals(UUID workflow) {
        List<Map<String, Object>> rows = jdbc().queryForList(
                "SELECT rating_count, rating_sum FROM workflow_rating_total WHERE workflow_id = ?", workflow);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    private void assertTotals(UUID workflow, int count, int sum) {
        assertThat(totals(workflow)).containsEntry("rating_count", count).containsEntry("rating_sum", sum);
    }

    @Test
    void backfillEqualsExistingReviews() throws IOException {
        UUID rated = publicWorkflow();
        UUID unrated = publicWorkflow();
        insertReview(rated, 5);
        insertReview(rated, 4);
        insertReview(rated, 1);
        String script = Files.readString(Path.of(migrationsDir, MIGRATION));
        String backfill = script.substring(script.indexOf("INSERT INTO workflow_rating_total"));

        jdbc().update("DELETE FROM workflow_rating_total");
        jdbc().execute(backfill);

        assertTotals(rated, 3, 10);
        assertThat(totals(unrated)).isEmpty();
        assertThat(jdbc().queryForObject("""
                SELECT count(*) FROM (
                    SELECT workflow_id, count(*)::int, sum(rating)::int FROM workflow_review GROUP BY workflow_id
                    EXCEPT
                    SELECT workflow_id, rating_count, rating_sum FROM workflow_rating_total) missing
                """, Long.class)).isZero();
    }

    @Test
    void concurrentFirstReviewsByTwoMembersBothCount() throws Exception {
        UUID workflow = publicWorkflow();
        UUID ana = memberWithCompletedRun(workflow);
        UUID ben = memberWithCompletedRun(workflow);

        together(List.of(() -> service.write(ana, workflow, 5, null, null),
                () -> service.write(ben, workflow, 2, null, null)));

        assertTotals(workflow, 2, 7);
    }

    @Test
    void oneMemberWritingTwiceAtOnceCountsOnce() throws Exception {
        UUID workflow = publicWorkflow();
        UUID ana = memberWithCompletedRun(workflow);

        together(List.of(() -> service.write(ana, workflow, 5, null, null),
                () -> service.write(ana, workflow, 2, null, null)));

        int stored = reviewRepository.findByWorkflowIdAndUserId(workflow, ana).orElseThrow().getRating();
        assertTotals(workflow, 1, stored);
    }

    @Test
    void replaceThenDeleteReturnsToZero() {
        UUID workflow = publicWorkflow();
        UUID ana = memberWithCompletedRun(workflow);

        service.write(ana, workflow, 4, null, null);
        assertTotals(workflow, 1, 4);
        service.write(ana, workflow, 2, "changed my mind", null);
        assertTotals(workflow, 1, 2);
        service.write(ana, workflow, 2, "same stars", null);
        assertTotals(workflow, 1, 2);
        service.deleteMine(ana, workflow);

        assertTotals(workflow, 0, 0);
    }

    @Test
    void rolledBackReviewLeavesTotalsUnchanged() {
        UUID workflow = publicWorkflow();
        UUID ana = memberWithCompletedRun(workflow);
        UUID ben = memberWithCompletedRun(workflow);
        service.write(ana, workflow, 4, null, null);
        when(mediaJobs.resolveImageUploads(eq(UploadPurpose.REVIEW_IMAGE), eq(ben), anyList()))
                .thenReturn(List.<MediaAssetInput>of());
        // A bucket name longer than its column: the write fails inside the transaction, after the totals changed.
        when(mediaJobs.keepAttachments(anyString(), anyList(), anyLong())).thenReturn(
                List.of(new StoredAttachment("b".repeat(64), "key", "image/png", 10)));

        assertThatThrownBy(() -> service.write(ben, workflow, 5, null, List.of(UUID.randomUUID())))
                .isInstanceOf(RuntimeException.class);

        assertThat(reviewRepository.findByWorkflowIdAndUserId(workflow, ben)).isEmpty();
        assertTotals(workflow, 1, 4);
    }

    @Test
    void workflowSearchFoldStripsVietnameseAccents() {
        assertThat(jdbc().queryForObject("SELECT workflow_search_fold('Tạo Video Đẹp TIẾNG Việt')", String.class))
                .isEqualTo("tao video dep tieng viet");
        assertThat(jdbc().queryForObject("SELECT workflow_search_fold('Ta' || chr(803) || 'o')", String.class))
                .isEqualTo("tao");
    }

    /** Runs every task on its own thread, released together, and fails with the first failure. */
    private static void together(List<Runnable> tasks) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(tasks.size());
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<?>> results = new ArrayList<>();
            for (Runnable task : tasks) {
                results.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    task.run();
                    return null;
                }));
            }
            for (Future<?> result : results) {
                result.get(20, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
