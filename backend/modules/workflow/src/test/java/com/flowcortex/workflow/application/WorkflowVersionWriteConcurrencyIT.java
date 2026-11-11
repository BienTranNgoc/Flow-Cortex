package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.workflow.api.VersionSavedView;
import com.flowcortex.workflow.api.WorkflowVersionService;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowStep;
import com.flowcortex.workflow.domain.WorkflowVersion;
import com.flowcortex.workflow.domain.WorkflowVersionPin;
import com.flowcortex.workflow.domain.WorkflowVersionSource;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import com.flowcortex.workflow.infrastructure.WorkflowStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowVersionPinRepository;
import com.flowcortex.workflow.infrastructure.WorkflowVersionRepository;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Concurrent version writes to one workflow go through the production beans on a real Postgres: the row lock taken
 * by the write arbiter serializes "read the newest, compare, insert the next number", and the pin upsert never
 * surfaces a key clash. {@code NOT_SUPPORTED} so the seeded rows are committed before the threads race.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({WorkflowVersionServiceImpl.class, WorkflowWriteAccess.class, VersionWriter.class,
        WorkflowVersionWriteConcurrencyIT.Beans.class})
@Testcontainers
class WorkflowVersionWriteConcurrencyIT {

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
    }

    @Autowired
    private WorkflowVersionService versionService;

    @Autowired
    private WorkflowWriteAccess writeAccess;

    @Autowired
    private VersionWriter versionWriter;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private WorkflowStepRepository workflowStepRepository;

    @Autowired
    private WorkflowVersionRepository versionRepository;

    @Autowired
    private WorkflowVersionPinRepository pinRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private List<WorkflowStep> seedSteps(Workflow workflow, int count) {
        List<WorkflowStep> steps = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            steps.add(new WorkflowStep(UUID.randomUUID(), workflow.getId(), (short) i, "s" + i, "echo", null));
        }
        return workflowStepRepository.saveAll(steps);
    }

    private <T> List<T> raceAll(Callable<T> work) throws Exception {
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<T>> results = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return work.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<T> values = new ArrayList<>();
            for (Future<T> result : results) {
                values.add(result.get(30, TimeUnit.SECONDS));
            }
            return values;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void eightConcurrentSavesOfOneDefinitionWriteOneRow() throws Exception {
        UUID ownerId = UUID.randomUUID();
        Workflow workflow = workflowRepository.saveAndFlush(Workflow.draft(UUID.randomUUID(), ownerId, "Race", false));
        seedSteps(workflow, 2);

        List<VersionSavedView> saved = raceAll(() -> versionService.save(ownerId, workflow.getId(), null));

        assertThat(saved).extracting(view -> view.version().version()).containsOnly(1);
        assertThat(saved).filteredOn(view -> !view.deduplicated()).hasSize(1);
        assertThat(versionRepository.findAll()).filteredOn(v -> v.getWorkflowId().equals(workflow.getId()))
                .hasSize(1);
    }

    @Test
    void concurrentSavesAfterDistinctEditsGetConsecutiveNumbers() throws Exception {
        UUID ownerId = UUID.randomUUID();
        Workflow workflow = workflowRepository.saveAndFlush(Workflow.draft(UUID.randomUUID(), ownerId, "Race", false));
        List<WorkflowStep> steps = seedSteps(workflow, THREADS);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        AtomicInteger next = new AtomicInteger();

        List<Integer> numbers = raceAll(() -> {
            int index = next.getAndIncrement();
            return transaction.execute(status -> {
                Workflow locked = writeAccess.lockForWrite(Caller.member(ownerId), workflow.getId());
                WorkflowStep step = workflowStepRepository.findById(steps.get(index).getId()).orElseThrow();
                step.edit(step.getName(), "{\"n\":" + index + "}", step.getInputRef());
                workflowStepRepository.saveAndFlush(step);
                return versionWriter.append(locked,
                        workflowStepRepository.findByWorkflowIdOrderByStepOrderAsc(workflow.getId()),
                        WorkflowVersionSource.MANUAL, null, null, ownerId).version().getVersion();
            });
        });

        assertThat(numbers).doesNotHaveDuplicates();
        assertThat(numbers).containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6, 7, 8);
        assertThat(versionRepository.findAll()).filteredOn(v -> v.getWorkflowId().equals(workflow.getId()))
                .hasSize(THREADS);
    }

    @Test
    void twoConcurrentFirstPinsLeaveOnePin() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        Workflow workflow = workflowRepository.saveAndFlush(new Workflow(UUID.randomUUID(), ownerId, "Shared", true));
        for (int number = 1; number <= 2; number++) {
            WorkflowVersion version = new WorkflowVersion(UUID.randomUUID(), workflow.getId(), number, null,
                    WorkflowVersionSource.PUBLISH, null, WorkflowSnapshots.of(workflow, List.of()).toString(), ownerId,
                    Instant.now());
            version.release(Instant.now());
            versionRepository.saveAndFlush(version);
        }
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int number = 1; number <= 2; number++) {
                int pinned = number;
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return versionService.pin(memberId, workflow.getId(), pinned, null).pinnedVersion();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (Future<Integer> result : results) {
                result.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        List<WorkflowVersionPin> pins = pinRepository.findByUserIdAndWorkflowIdIn(memberId, List.of(workflow.getId()));
        assertThat(pins).hasSize(1);
        assertThat(pins.getFirst().getPinnedVersion()).isIn(1, 2);
    }
}
