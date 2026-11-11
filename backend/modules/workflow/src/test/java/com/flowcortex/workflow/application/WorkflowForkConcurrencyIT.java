package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.workflow.api.WorkflowView;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowStep;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import com.flowcortex.workflow.infrastructure.WorkflowStepRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Forks go through the production service on a real Postgres: concurrent forks of one workflow never lose a count,
 * and a fork whose step insert fails leaves neither a workflow row nor a count. {@code NOT_SUPPORTED} so the seeded
 * rows are committed before the threads race.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({WorkflowForkService.class, ServedDefinitions.class, WorkflowForkConcurrencyIT.Beans.class})
@Testcontainers
class WorkflowForkConcurrencyIT {

    private static final int FORKS = 8;

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
    }

    @Autowired
    private WorkflowForkService forkService;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private WorkflowStepRepository workflowStepRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private Workflow forkableSource(String stepName) {
        Workflow source = new Workflow(UUID.randomUUID(), UUID.randomUUID(), "Source", true);
        source.setAllowFork(true);
        workflowRepository.saveAndFlush(source);
        workflowStepRepository.saveAndFlush(
                new WorkflowStep(UUID.randomUUID(), source.getId(), (short) 1, stepName, "echo", null));
        return source;
    }

    private int countOf(UUID workflowId) {
        return jdbc.queryForObject("SELECT fork_count FROM workflow WHERE id = ?", Integer.class, workflowId);
    }

    private int forksOf(UUID workflowId) {
        return jdbc.queryForObject("SELECT count(*) FROM workflow WHERE forked_from_id = ?", Integer.class,
                workflowId);
    }

    @Test
    void eightConcurrentForksCountEight() throws Exception {
        Workflow source = forkableSource("only");

        CountDownLatch ready = new CountDownLatch(FORKS);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(FORKS);
        try {
            List<Future<WorkflowView>> results = new ArrayList<>();
            for (int i = 0; i < FORKS; i++) {
                UUID caller = UUID.randomUUID();
                Callable<WorkflowView> fork = () -> {
                    ready.countDown();
                    go.await();
                    return forkService.fork(caller, source.getId());
                };
                results.add(pool.submit(fork));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (Future<WorkflowView> result : results) {
                result.get(30, TimeUnit.SECONDS);
            }

            assertThat(countOf(source.getId())).isEqualTo(FORKS);
            assertThat(forksOf(source.getId())).isEqualTo(FORKS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aForkWhoseStepInsertFailsLeavesNoRowAndNoCount() {
        Workflow source = forkableSource("poison");
        jdbc.execute("ALTER TABLE workflow_step ADD CONSTRAINT ck_no_poison CHECK (name <> 'poison') NOT VALID");
        try {
            assertThatThrownBy(() -> forkService.fork(UUID.randomUUID(), source.getId()))
                    .isInstanceOf(DataIntegrityViolationException.class);

            assertThat(countOf(source.getId())).isZero();
            assertThat(forksOf(source.getId())).isZero();
        } finally {
            jdbc.execute("ALTER TABLE workflow_step DROP CONSTRAINT ck_no_poison");
        }
    }
}
