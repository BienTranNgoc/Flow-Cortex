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
 * Single-active dispatch: two concurrent attempts to claim the SAME pending
 * step must leave EXACTLY ONE winner. Exercises the actual production bean
 * ({@link RunStepTransactions#claim}, {@code @Transactional(REQUIRES_NEW)})
 * so the test proves the SAME code path the orchestrator uses — an atomic
 * {@code UPDATE run_step SET status = 'running' ... WHERE status =
 * 'pending'} acted on by row count, not an application-level
 * read-then-check. Simulates "original execution + a redelivered event"
 * racing the same step.
 *
 * <p>Lives in this module's own {@code @DataJpaTest} (package-private access
 * to {@link RunStepTransactions} requires being in the same package) — {@link
 * com.flowcortex.workflow.TestWorkflowBootApp} supplies the {@code
 * @SpringBootConfiguration} it needs — rather than {@code :boot:app}: this is
 * internal engine machinery with no {@code api} port, so there is nothing to
 * reach from outside this module. A real Postgres (no H2) is required to
 * prove genuine cross-connection SQL atomicity, not merely single-threaded
 * correctness.
 *
 * <p>{@code @Transactional(NOT_SUPPORTED)} deliberately opts OUT of {@code
 * @DataJpaTest}'s default rolled-back test transaction: the seeded step must
 * be REALLY committed before the two worker threads race to claim it (each
 * going through {@code RunStepTransactions}' OWN REQUIRES_NEW transaction),
 * or neither would see it yet.
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
