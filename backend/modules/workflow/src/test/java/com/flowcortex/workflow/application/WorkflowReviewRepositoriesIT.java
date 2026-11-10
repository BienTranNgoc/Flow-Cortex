package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.workflow.domain.ReviewReportReason;
import com.flowcortex.workflow.domain.ReviewSort;
import com.flowcortex.workflow.domain.RunStatus;
import com.flowcortex.workflow.domain.WorkflowReview;
import com.flowcortex.workflow.domain.WorkflowReviewImage;
import com.flowcortex.workflow.infrastructure.BookmarkedWorkflowRow;
import com.flowcortex.workflow.infrastructure.RatingCount;
import com.flowcortex.workflow.infrastructure.WorkflowBookmarkRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import com.flowcortex.workflow.infrastructure.WorkflowReviewHelpfulRepository;
import com.flowcortex.workflow.infrastructure.WorkflowReviewImageRepository;
import com.flowcortex.workflow.infrastructure.WorkflowReviewReportRepository;
import com.flowcortex.workflow.infrastructure.WorkflowReviewRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The bookmark and review schema and queries against a real Postgres on the Flyway-migrated
 * schema, so its CHECKs, unique keys and deferred position key apply. Nothing runs in a test
 * transaction: the concurrency cases need each side to really commit.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.flyway.locations=filesystem:${flowcortex.migrations.dir}",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class WorkflowReviewRepositoriesIT {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    private static final PageRequest FIRST_PAGE = PageRequest.of(0, 50);

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private WorkflowRunRepository runRepository;

    @Autowired
    private WorkflowBookmarkRepository bookmarkRepository;

    @Autowired
    private WorkflowReviewRepository reviewRepository;

    @Autowired
    private WorkflowReviewImageRepository imageRepository;

    @Autowired
    private WorkflowReviewHelpfulRepository helpfulRepository;

    @Autowired
    private WorkflowReviewReportRepository reportRepository;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    private <T> T inTx(Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private UUID insertWorkflow(UUID owner, String status, String visibility, boolean deleted) {
        UUID id = UUID.randomUUID();
        Instant at = now();
        String key = "unlisted".equals(visibility) ? UUID.randomUUID().toString().replace("-", "").substring(0, 22)
                : null;
        jdbc().update("INSERT INTO workflow (id, owner_id, name, is_shared, created_at, updated_at, status,"
                        + " deleted_at, visibility, unlisted_key) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, owner, "it-" + id, "public".equals(visibility), Timestamp.from(at), Timestamp.from(at), status,
                deleted ? Timestamp.from(at) : null, visibility, key);
        return id;
    }

    private UUID publicWorkflow() {
        return insertWorkflow(UUID.randomUUID(), "published", "public", false);
    }

    private void grant(UUID workflow, UUID user) {
        jdbc().update("INSERT INTO workflow_link_grant (workflow_id, user_id, created_at) VALUES (?, ?, now())",
                workflow, user);
    }

    private UUID insertRun(UUID workflow, UUID owner, String status) {
        UUID id = UUID.randomUUID();
        jdbc().update("INSERT INTO workflow_run (id, workflow_id, owner_id, status, started_at)"
                + " VALUES (?, ?, ?, ?, now())", id, workflow, owner, status);
        return id;
    }

    private UUID insertReview(UUID workflow, UUID user, int rating, String body, int helpful, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc().update("INSERT INTO workflow_review (id, workflow_id, user_id, rating, body, helpful_count,"
                        + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id, workflow, user, rating, body, helpful, Timestamp.from(createdAt), Timestamp.from(createdAt));
        return id;
    }

    private UUID insertImage(UUID review, int position) {
        UUID id = UUID.randomUUID();
        jdbc().update("INSERT INTO workflow_review_image (id, review_id, position, bucket, object_key,"
                        + " content_type, size_bytes, created_at) VALUES (?, ?, ?, 'b', ?, 'image/png', 10, now())",
                id, review, position, "workflow-review/" + id);
        return id;
    }

    private long count(String table, String column, UUID value) {
        return jdbc().queryForObject("SELECT count(*) FROM " + table + " WHERE " + column + " = ?", Long.class,
                value);
    }

    private static Set<UUID> ids(List<BookmarkedWorkflowRow> rows) {
        return rows.stream().map(row -> row.workflow().getId()).collect(Collectors.toSet());
    }

    @Test
    void theSavedListEqualsWhatFindVisibleToAdmits() {
        UUID owner = UUID.randomUUID();
        UUID ben = UUID.randomUUID();
        List<UUID> all = List.of(
                insertWorkflow(ben, "draft", "private", false),
                insertWorkflow(owner, "published", "private", false),
                insertWorkflow(owner, "draft", "public", false),
                insertWorkflow(owner, "published", "public", false),
                insertWorkflow(owner, "published", "unlisted", false),
                insertWorkflow(owner, "published", "unlisted", false),
                insertWorkflow(owner, "draft", "unlisted", false),
                insertWorkflow(owner, "published", "public", true));
        grant(all.get(5), ben);
        grant(all.get(6), ben);
        Instant at = now();
        for (UUID workflow : all) {
            inTx(() -> bookmarkRepository.insertIfAbsent(ben, workflow, at));
        }

        Set<UUID> admitted = all.stream().filter(w -> workflowRepository.findVisibleTo(w, ben).isPresent())
                .collect(Collectors.toSet());

        assertThat(admitted).containsExactlyInAnyOrder(all.get(0), all.get(3), all.get(5));
        assertThat(ids(bookmarkRepository.pageVisible(ben, FIRST_PAGE))).isEqualTo(admitted);
        assertThat(bookmarkRepository.countVisible(ben)).isEqualTo(admitted.size());
    }

    @Test
    void anUnlistedWorkflowWithAGrantIsListedAndCounted() {
        UUID ben = UUID.randomUUID();
        UUID granted = insertWorkflow(UUID.randomUUID(), "published", "unlisted", false);
        UUID notGranted = insertWorkflow(UUID.randomUUID(), "published", "unlisted", false);
        grant(granted, ben);
        inTx(() -> bookmarkRepository.insertIfAbsent(ben, granted, now()));
        inTx(() -> bookmarkRepository.insertIfAbsent(ben, notGranted, now()));

        assertThat(ids(bookmarkRepository.pageVisible(ben, FIRST_PAGE))).containsExactly(granted);
        assertThat(bookmarkRepository.countVisible(ben)).isEqualTo(1);
    }

    @Test
    void theSavedListIsNewestFirstAndCountsOnlyVisibleWorkflows() {
        UUID ben = UUID.randomUUID();
        UUID oldest = publicWorkflow();
        UUID middle = publicWorkflow();
        UUID newest = publicWorkflow();
        UUID hidden = insertWorkflow(UUID.randomUUID(), "published", "private", false);
        Instant base = now();
        inTx(() -> bookmarkRepository.insertIfAbsent(ben, middle, base.minusSeconds(20)));
        inTx(() -> bookmarkRepository.insertIfAbsent(ben, newest, base));
        inTx(() -> bookmarkRepository.insertIfAbsent(ben, hidden, base.plusSeconds(60)));
        inTx(() -> bookmarkRepository.insertIfAbsent(ben, oldest, base.minusSeconds(40)));

        List<BookmarkedWorkflowRow> rows = bookmarkRepository.pageVisible(ben, FIRST_PAGE);

        assertThat(rows).extracting(row -> row.workflow().getId()).containsExactly(newest, middle, oldest);
        assertThat(rows.get(0).bookmarkedAt()).isEqualTo(base);
        assertThat(bookmarkRepository.countVisible(ben)).isEqualTo(3);
        assertThat(bookmarkRepository.pageVisible(ben, PageRequest.of(1, 2))).extracting(
                row -> row.workflow().getId()).containsExactly(oldest);
    }

    @Test
    void twoConcurrentBookmarkInsertsLeaveOneRow() throws Exception {
        UUID ben = UUID.randomUUID();
        UUID workflow = publicWorkflow();

        int written = together(2, () -> inTx(() -> bookmarkRepository.insertIfAbsent(ben, workflow, now())));

        assertThat(written).isEqualTo(1);
        assertThat(count("workflow_bookmark", "workflow_id", workflow)).isEqualTo(1);
        assertThat(inTx(() -> bookmarkRepository.deleteByUserIdAndWorkflowId(ben, workflow))).isEqualTo(1);
        assertThat(bookmarkRepository.existsByUserIdAndWorkflowId(ben, workflow)).isFalse();
    }

    @Test
    void aCompletedRunQualifiesAndAnErroredOneDoesNot() {
        UUID workflow = publicWorkflow();
        UUID ana = UUID.randomUUID();
        UUID ben = UUID.randomUUID();
        UUID cho = UUID.randomUUID();
        insertRun(workflow, ana, "completed");
        insertRun(workflow, ben, "error");
        insertRun(workflow, cho, "running");

        assertThat(runRepository.existsByWorkflowIdAndOwnerIdAndStatus(workflow, ana, RunStatus.COMPLETED)).isTrue();
        assertThat(runRepository.existsByWorkflowIdAndOwnerIdAndStatus(workflow, ben, RunStatus.COMPLETED)).isFalse();
        assertThat(runRepository.existsByWorkflowIdAndOwnerIdAndStatus(workflow, cho, RunStatus.COMPLETED)).isFalse();
        assertThat(runRepository.existsByWorkflowIdAndOwnerIdAndStatus(publicWorkflow(), ana, RunStatus.COMPLETED))
                .isFalse();
    }

    @Test
    void twoConcurrentFirstReviewsLeaveOneReview() throws Exception {
        UUID workflow = publicWorkflow();
        UUID ben = UUID.randomUUID();

        int written = together(2, () -> inTx(() -> reviewRepository.insertIfAbsent(UUID.randomUUID(), workflow, ben,
                5, null, now())));

        assertThat(written).isEqualTo(1);
        assertThat(count("workflow_review", "workflow_id", workflow)).isEqualTo(1);
        WorkflowReview locked = inTx(() -> {
            WorkflowReview review = reviewRepository.lockByWorkflowIdAndUserId(workflow, ben).orElseThrow();
            review.replace(2, "later", now());
            return reviewRepository.saveAndFlush(review);
        });
        WorkflowReview stored = reviewRepository.findByWorkflowIdAndUserId(workflow, ben).orElseThrow();
        assertThat(stored.getRating()).isEqualTo(2);
        assertThat(stored.getBody()).isEqualTo("later");
        assertThat(stored.getCreatedAt()).isEqualTo(locked.getCreatedAt());
    }

    @Test
    void concurrentHelpfulMarksByTwoMembersCountTwo() throws Exception {
        UUID workflow = publicWorkflow();
        UUID review = insertReview(workflow, UUID.randomUUID(), 4, null, 0, now());
        UUID ana = UUID.randomUUID();
        UUID ben = UUID.randomUUID();
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> marks = new ArrayList<>();
            for (UUID member : List.of(ana, ben)) {
                marks.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return inTx(() -> {
                        int written = helpfulRepository.insertIfAbsent(review, member, now());
                        reviewRepository.addHelpful(review, written);
                        return written;
                    });
                }));
            }
            for (Future<Integer> mark : marks) {
                assertThat(mark.get(20, TimeUnit.SECONDS)).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(reviewRepository.findById(review).orElseThrow().getHelpfulCount()).isEqualTo(2);
        assertThat(count("workflow_review_helpful", "review_id", review)).isEqualTo(2);
    }

    @Test
    void lockingAReviewByIdFindsOnlyItsOwnWorkflowsReview() {
        UUID workflow = publicWorkflow();
        UUID other = publicWorkflow();
        UUID review = insertReview(workflow, UUID.randomUUID(), 4, null, 0, now());

        assertThat(inTx(() -> reviewRepository.lockByIdAndWorkflowId(review, workflow))).isPresent();
        assertThat(inTx(() -> reviewRepository.lockByIdAndWorkflowId(review, other))).isEmpty();
        assertThat(inTx(() -> reviewRepository.lockByIdAndWorkflowId(UUID.randomUUID(), workflow))).isEmpty();
    }

    @Test
    void aHeldReviewLockHoldsBackADeleteButNotAHelpfulCountUpdate() throws Exception {
        UUID workflow = publicWorkflow();
        UUID review = insertReview(workflow, UUID.randomUUID(), 4, null, 0, now());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            inTx(() -> {
                assertThat(reviewRepository.lockByIdAndWorkflowId(review, workflow)).isPresent();
                Future<Throwable> blockedDelete = pool.submit(() -> failureOf(() -> inTx(() -> {
                    jdbc().execute("SET LOCAL lock_timeout = '500ms'");
                    return jdbc().update("DELETE FROM workflow_review WHERE id = ?", review);
                })));
                Future<Integer> update = pool.submit(() -> inTx(() -> {
                    jdbc().execute("SET LOCAL lock_timeout = '5s'");
                    return reviewRepository.addHelpful(review, 1);
                }));
                try {
                    assertThat(blockedDelete.get(20, TimeUnit.SECONDS)).hasMessageContaining("lock timeout");
                    assertThat(update.get(20, TimeUnit.SECONDS)).isEqualTo(1);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
                return null;
            });
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbc().update("DELETE FROM workflow_review WHERE id = ?", review)).isEqualTo(1);
    }

    @Test
    void twoMembersHoldingTheReviewLockCanBothMarkItHelpful() throws Exception {
        UUID workflow = publicWorkflow();
        UUID review = insertReview(workflow, UUID.randomUUID(), 4, null, 0, now());
        CyclicBarrier bothLocked = new CyclicBarrier(2);

        int marked = together(2, () -> inTx(() -> {
            assertThat(reviewRepository.lockByIdAndWorkflowId(review, workflow)).isPresent();
            try {
                bothLocked.await(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            int written = helpfulRepository.insertIfAbsent(review, UUID.randomUUID(), now());
            reviewRepository.addHelpful(review, written);
            return written;
        }));

        assertThat(marked).isEqualTo(2);
        assertThat(reviewRepository.findById(review).orElseThrow().getHelpfulCount()).isEqualTo(2);
    }

    @Test
    void aRepeatedHelpfulMarkChangesNothing() {
        UUID workflow = publicWorkflow();
        UUID review = insertReview(workflow, UUID.randomUUID(), 4, null, 0, now());
        UUID ben = UUID.randomUUID();

        int first = inTx(() -> {
            int written = helpfulRepository.insertIfAbsent(review, ben, now());
            reviewRepository.addHelpful(review, written);
            return written;
        });
        int second = inTx(() -> {
            int written = helpfulRepository.insertIfAbsent(review, ben, now());
            reviewRepository.addHelpful(review, written);
            return written;
        });

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(reviewRepository.findById(review).orElseThrow().getHelpfulCount()).isEqualTo(1);
        assertThat(helpfulRepository.findMarkedReviewIds(ben, List.of(review, UUID.randomUUID())))
                .containsExactly(review);

        int removed = inTx(() -> {
            int deleted = helpfulRepository.deleteMark(review, ben);
            reviewRepository.addHelpful(review, -deleted);
            return deleted;
        });
        int removedAgain = inTx(() -> helpfulRepository.deleteMark(review, ben));

        assertThat(removed).isEqualTo(1);
        assertThat(removedAgain).isZero();
        assertThat(reviewRepository.findById(review).orElseThrow().getHelpfulCount()).isZero();
        assertThat(helpfulRepository.findMarkedReviewIds(ben, List.of(review))).isEmpty();
    }

    @Test
    void aRepeatedReportStoresOne() {
        UUID workflow = publicWorkflow();
        UUID review = insertReview(workflow, UUID.randomUUID(), 1, "bad", 0, now());
        UUID ben = UUID.randomUUID();

        int first = inTx(() -> reportRepository.insertIfAbsent(review, ben, ReviewReportReason.SPAM.code(), "x",
                now()));
        int second = inTx(() -> reportRepository.insertIfAbsent(review, ben, ReviewReportReason.SCAM.code(), null,
                now()));

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(jdbc().queryForObject("SELECT reason FROM workflow_review_report WHERE review_id = ?",
                String.class, review)).isEqualTo("spam");
        assertThat(reportRepository.findAll()).filteredOn(report -> report.getReviewId().equals(review))
                .singleElement().satisfies(report -> assertThat(report.getReason())
                        .isEqualTo(ReviewReportReason.SPAM));
    }

    @Test
    void deletingAReviewRemovesItsImagesMarksAndReports() {
        UUID workflow = publicWorkflow();
        UUID review = insertReview(workflow, UUID.randomUUID(), 3, "ok", 1, now());
        insertImage(review, 1);
        insertImage(review, 2);
        inTx(() -> helpfulRepository.insertIfAbsent(review, UUID.randomUUID(), now()));
        inTx(() -> reportRepository.insertIfAbsent(review, UUID.randomUUID(), "other", null, now()));

        inTx(() -> {
            reviewRepository.deleteById(review);
            reviewRepository.flush();
            return null;
        });

        assertThat(count("workflow_review_image", "review_id", review)).isZero();
        assertThat(count("workflow_review_helpful", "review_id", review)).isZero();
        assertThat(count("workflow_review_report", "review_id", review)).isZero();
        assertThat(inTx(() -> imageRepository.deleteByReviewId(review))).isZero();
    }

    @Test
    void theGroupedRatingsQueryCountsPerStar() {
        UUID workflow = publicWorkflow();
        for (int rating : new int[] {5, 5, 4, 1}) {
            insertReview(workflow, UUID.randomUUID(), rating, null, 0, now());
        }
        insertReview(publicWorkflow(), UUID.randomUUID(), 2, null, 0, now());

        Map<Integer, Long> counts = reviewRepository.ratingCounts(workflow).stream()
                .collect(Collectors.toMap(RatingCount::getRating, RatingCount::getTotal));

        assertThat(counts).containsOnly(Map.entry(5, 2L), Map.entry(4, 1L), Map.entry(1, 1L));
        assertThat(reviewRepository.ratingCounts(publicWorkflow())).isEmpty();
    }

    @Test
    void listFiltersByRatingCommentAndImagesAndSortsByHelpfulThenNewest() {
        UUID workflow = publicWorkflow();
        Instant base = now();
        UUID oldHelpful = insertReview(workflow, UUID.randomUUID(), 5, "great", 3, base.minusSeconds(30));
        UUID newHelpful = insertReview(workflow, UUID.randomUUID(), 5, null, 3, base.minusSeconds(10));
        UUID plain = insertReview(workflow, UUID.randomUUID(), 4, "fine", 0, base);
        UUID noMark = insertReview(workflow, UUID.randomUUID(), 5, "ok", 1, base.minusSeconds(20));
        insertImage(newHelpful, 1);
        insertImage(plain, 1);
        insertImage(plain, 2);

        assertThat(page(workflow, null, false, false, ReviewSort.NEWEST, 0, 10))
                .containsExactly(plain, newHelpful, noMark, oldHelpful);
        assertThat(page(workflow, null, false, false, ReviewSort.HELPFUL, 0, 10))
                .containsExactly(newHelpful, oldHelpful, noMark, plain);
        assertThat(page(workflow, 5, false, false, ReviewSort.NEWEST, 0, 10))
                .containsExactly(newHelpful, noMark, oldHelpful);
        assertThat(page(workflow, null, true, false, ReviewSort.NEWEST, 0, 10))
                .containsExactly(plain, noMark, oldHelpful);
        assertThat(page(workflow, null, false, true, ReviewSort.NEWEST, 0, 10))
                .containsExactly(plain, newHelpful);
        assertThat(page(workflow, 5, true, true, ReviewSort.NEWEST, 0, 10)).isEmpty();
        assertThat(page(workflow, null, false, false, ReviewSort.HELPFUL, 1, 2)).containsExactly(noMark, plain);

        assertThat(reviewRepository.count(workflow, null, false, false)).isEqualTo(4);
        assertThat(reviewRepository.count(workflow, 5, false, false)).isEqualTo(3);
        assertThat(reviewRepository.count(workflow, null, true, false)).isEqualTo(3);
        assertThat(reviewRepository.count(workflow, null, false, true)).isEqualTo(2);
        assertThat(reviewRepository.count(workflow, 4, true, true)).isEqualTo(1);

        assertThat(imageRepository.findByReviewIdInOrderByReviewIdAscPosition(List.of(plain, newHelpful)))
                .extracting(WorkflowReviewImage::getReviewId, WorkflowReviewImage::getPosition)
                .hasSize(3)
                .containsSubsequence(org.assertj.core.groups.Tuple.tuple(plain, 1),
                        org.assertj.core.groups.Tuple.tuple(plain, 2));
        assertThat(imageRepository.findByReviewIdOrderByPosition(plain)).extracting(WorkflowReviewImage::getPosition)
                .containsExactly(1, 2);
    }

    private List<UUID> page(UUID workflow, Integer rating, boolean withComment, boolean withImages, ReviewSort sort,
            int pageIndex, int size) {
        return reviewRepository.page(workflow, rating, withComment, withImages, sort, PageRequest.of(pageIndex, size))
                .stream().map(WorkflowReview::getId).toList();
    }

    @Test
    void aReviewIdOfAnotherWorkflowIsNotFound() {
        UUID workflow = publicWorkflow();
        UUID other = publicWorkflow();
        UUID review = insertReview(workflow, UUID.randomUUID(), 4, null, 0, now());

        assertThat(reviewRepository.findByIdAndWorkflowId(review, workflow)).isPresent();
        assertThat(reviewRepository.findByIdAndWorkflowId(review, other)).isEmpty();
    }

    @Test
    void positionsOfKeptImagesCanBeRewrittenInOneTransaction() {
        UUID workflow = publicWorkflow();
        UUID review = insertReview(workflow, UUID.randomUUID(), 4, null, 0, now());
        UUID first = insertImage(review, 1);
        UUID second = insertImage(review, 2);

        inTx(() -> {
            List<WorkflowReviewImage> images = imageRepository.findByReviewIdOrderByPosition(review);
            images.get(0).moveTo(2);
            images.get(1).moveTo(1);
            imageRepository.saveAllAndFlush(images);
            return null;
        });

        assertThat(imageRepository.findByReviewIdOrderByPosition(review)).extracting(WorkflowReviewImage::getId)
                .containsExactly(second, first);
    }

    @Test
    void ratingOutsideOneToFiveAndANegativeHelpfulCountAreRefusedByTheDatabase() {
        UUID workflow = publicWorkflow();
        UUID review = insertReview(workflow, UUID.randomUUID(), 4, null, 0, now());
        Set<UUID> reviewers = new HashSet<>();

        for (int rating : new int[] {0, 6}) {
            UUID reviewer = UUID.randomUUID();
            reviewers.add(reviewer);
            assertThatThrownBy(() -> insertReview(workflow, reviewer, rating, null, 0, now()))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> insertReview(workflow, UUID.randomUUID(), 3, null, -1, now()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> inTx(() -> reviewRepository.addHelpful(review, -1)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(reviewRepository.findById(review).orElseThrow().getHelpfulCount()).isZero();
        assertThat(reviewers).allSatisfy(reviewer ->
                assertThat(reviewRepository.findByWorkflowIdAndUserId(workflow, reviewer)).isEmpty());
    }

    private static Throwable failureOf(Runnable work) {
        try {
            work.run();
        } catch (RuntimeException e) {
            Throwable root = e;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            return root;
        }
        throw new AssertionError("expected the call to fail");
    }

    /** Runs {@code work} on {@code threads} threads released together and returns the sum of their results. */
    private int together(int threads, Supplier<Integer> work) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return work.get();
                }));
            }
            int sum = 0;
            for (Future<Integer> result : results) {
                sum += result.get(20, TimeUnit.SECONDS);
            }
            return sum;
        } finally {
            pool.shutdownNow();
        }
    }
}
