package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.workflow.application.WorkflowTransferService.ImportResult;
import com.flowcortex.workflow.application.WorkflowTransferService.ReviewView;
import com.flowcortex.workflow.domain.WorkflowImportReview;
import com.flowcortex.workflow.infrastructure.WorkflowImportReviewRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Review and import go through the production service on a real Postgres: a token imports once however many
 * requests race for it, the per-admin lock keeps the open reviews at the cap, and the purge keeps the ledger.
 * {@code NOT_SUPPORTED} so each seeded row is committed before the threads race.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({WorkflowTransferService.class, WorkflowWriteAccess.class, WorkflowReadinessGuard.class,
        ImportReviewPurgeJob.class, WorkflowImportConcurrencyIT.Beans.class})
@Testcontainers
class WorkflowImportConcurrencyIT {

    private static final int THREADS = 8;

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    @TestConfiguration
    static class Beans {

        @Bean
        StepExecutorRegistry stepExecutorRegistry() {
            return new StepExecutorRegistry(mock(MediaJobs.class), Clock.systemUTC());
        }

        @Bean
        Validator validator() {
            return Validation.buildDefaultValidatorFactory().getValidator();
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    @Autowired
    private WorkflowTransferService service;

    @Autowired
    private ImportReviewPurgeJob purgeJob;

    @Autowired
    private WorkflowImportReviewRepository reviewRepository;

    @Autowired
    private WorkflowRepository workflowRepository;

    private static byte[] artifact(String name) {
        return WorkflowArtifacts.write(name, List.of(new StepDefinition(1, "Say", "echo", "{\"a\":2.50}", null)),
                Instant.now()).toString().getBytes(StandardCharsets.UTF_8);
    }

    private <T> List<Future<T>> race(ExecutorService pool, Callable<T> task) throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> results = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            results.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return task.call();
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        return results;
    }

    @Test
    void twoConcurrentImportsOfOneTokenCreateOneWorkflow() throws Exception {
        UUID admin = UUID.randomUUID();
        ReviewView review = service.review(admin, artifact("Raced " + admin));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            Callable<ImportResult> importIt = () -> {
                ready.countDown();
                go.await();
                return service.importReviewed(admin, review.token().toString(), null);
            };
            Future<ImportResult> first = pool.submit(importIt);
            Future<ImportResult> second = pool.submit(importIt);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            ImportResult a = first.get(30, TimeUnit.SECONDS);
            ImportResult b = second.get(30, TimeUnit.SECONDS);

            assertThat(List.of(a.created(), b.created())).containsExactlyInAnyOrder(true, false);
            assertThat(a.view().id()).isEqualTo(b.view().id());
            assertThat(workflowRepository.findAllVisibleTo(admin)).hasSize(1);
            WorkflowImportReview ledger = reviewRepository.findById(review.token()).orElseThrow();
            assertThat(ledger.getWorkflowId()).isEqualTo(a.view().id());
            assertThat(ledger.getArtifact()).isNull();
            assertThat(ledger.getReview()).isNull();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentReviewsNeverLeaveMoreThanFiveOpen() throws Exception {
        UUID admin = UUID.randomUUID();
        byte[] body = artifact("Reviewed");

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<ReviewView>> results = race(pool, () -> service.review(admin, body));
            int accepted = 0;
            int refused = 0;
            for (Future<ReviewView> result : results) {
                try {
                    result.get(30, TimeUnit.SECONDS);
                    accepted++;
                } catch (ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOfSatisfying(LocalizedStatusException.class,
                            refusal -> assertThat(refusal.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
                    refused++;
                }
            }

            assertThat(accepted).isEqualTo(WorkflowTransferService.MAX_OPEN_REVIEWS);
            assertThat(refused).isEqualTo(THREADS - WorkflowTransferService.MAX_OPEN_REVIEWS);
            assertThat(reviewRepository.countByUserIdAndWorkflowIdIsNullAndExpiresAtAfter(admin, Instant.now()))
                    .isEqualTo(WorkflowTransferService.MAX_OPEN_REVIEWS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void thePurgeDeletesOnlyExpiredUnimportedReviews() {
        UUID admin = UUID.randomUUID();
        Instant now = Instant.now();
        byte[] file = artifact("Purged");
        String text = new String(file, StandardCharsets.UTF_8);

        WorkflowImportReview expired = new WorkflowImportReview(UUID.randomUUID(), admin, text, "{}", false,
                file.length, now.minus(Duration.ofHours(1)), now.minus(Duration.ofMinutes(45)));
        WorkflowImportReview open = new WorkflowImportReview(UUID.randomUUID(), admin, text, "{}", false,
                file.length, now.minus(Duration.ofMinutes(1)), now.plus(Duration.ofMinutes(14)));
        WorkflowImportReview importedLongAgo = new WorkflowImportReview(UUID.randomUUID(), admin, text, "{}", false,
                file.length, now.minus(Duration.ofDays(30)), now.minus(Duration.ofDays(30)).plus(Duration.ofMinutes(15)));
        importedLongAgo.markImported(UUID.randomUUID(), now.minus(Duration.ofDays(30)));
        reviewRepository.saveAllAndFlush(List.of(expired, open, importedLongAgo));

        purgeJob.purge();

        assertThat(reviewRepository.findById(expired.getToken())).isEmpty();
        assertThat(reviewRepository.findById(open.getToken())).isPresent();
        assertThat(reviewRepository.findById(importedLongAgo.getToken())).isPresent();
    }
}
