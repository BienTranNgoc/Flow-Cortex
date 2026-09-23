package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.domain.RunStepStatus;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Single-active dispatch: two concurrent attempts to claim the same pending step must leave
 * exactly one winner. Exercises the production bean ({@link RunStepTransactions#claim}, {@code
 * @Transactional(REQUIRES_NEW)}) so the test proves the same code path the orchestrator uses —
 * an atomic {@code UPDATE ... WHERE status = 'pending'} decided by row count, not
 * read-then-check. Simulates "original execution + a redelivered event" racing the same step.
 *
 * <p>Lives in this module's own {@code @DataJpaTest} (package-private access to {@link
 * RunStepTransactions} needs the same package) — {@link com.flowcortex.workflow.TestWorkflowBootApp}
 * supplies the {@code @SpringBootConfiguration}. A real Postgres (no H2) is required to prove
 * genuine cross-connection SQL atomicity, not just single-threaded correctness.
 *
 * <p>{@code @Transactional(NOT_SUPPORTED)} opts out of {@code @DataJpaTest}'s rolled-back test
 * transaction: the seeded step must be really committed before the two worker threads race to
 * claim it, or neither would see it yet.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(RunStepTransactions.class)
@Testcontainers
class RunStepClaimConcurrencyIT {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired
    private RunStepRepository runStepRepository;

    @Autowired
    private RunStepTransactions runStepTransactions;

    @Test
    void exactlyOneOfTwoConcurrentClaimsSucceeds() throws Exception {
        RunStep step = runStepRepository
                .saveAndFlush(new RunStep(UUID.randomUUID(), UUID.randomUUID(), (short) 1, "echo"));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> attempt = () -> {
                ready.countDown();
                go.await();
                return runStepTransactions.claim(step.getId(), Instant.now());
            };

            Future<Boolean> first = pool.submit(attempt);
            Future<Boolean> second = pool.submit(attempt);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            boolean firstWon = first.get(10, TimeUnit.SECONDS);
            boolean secondWon = second.get(10, TimeUnit.SECONDS);

            assertThat(firstWon ^ secondWon)
                    .as("exactly one of the two concurrent claims must win the pending -> running transition")
                    .isTrue();

            RunStep reloaded = runStepRepository.findById(step.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(RunStepStatus.RUNNING);
        } finally {
            pool.shutdownNow();
        }
    }
}
