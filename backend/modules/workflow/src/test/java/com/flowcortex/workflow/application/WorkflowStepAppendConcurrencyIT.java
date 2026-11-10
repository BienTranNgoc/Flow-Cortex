package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.flowcortex.credit.api.CreditAccounts;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.workflow.api.NewStep;
import com.flowcortex.workflow.api.WorkflowStepView;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowStep;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import com.flowcortex.workflow.infrastructure.WorkflowStepRepository;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Concurrent appends to one workflow go through the production service on a real Postgres: the row lock taken
 * before {@code MAX(step_order)} must hand every caller a distinct order. {@code NOT_SUPPORTED} so the seeded
 * workflow is committed before the threads race.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({WorkflowServiceImpl.class, WorkflowStepAppendConcurrencyIT.Beans.class})
@Testcontainers
class WorkflowStepAppendConcurrencyIT {

    private static final int APPENDS = 8;

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
        CreditAccounts creditAccounts() {
            return mock(CreditAccounts.class);
        }
    }

    @Autowired
    private WorkflowAuthoring authoring;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private WorkflowStepRepository workflowStepRepository;

    @Test
    void eightConcurrentAppendsGetDistinctGapFreeOrders() throws Exception {
        UUID ownerId = UUID.randomUUID();
        Workflow workflow = workflowRepository.saveAndFlush(Workflow.draft(UUID.randomUUID(), ownerId, "Race", false));

        CountDownLatch ready = new CountDownLatch(APPENDS);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(APPENDS);
        try {
            Callable<WorkflowStepView> append = () -> {
                ready.countDown();
                go.await();
                return authoring.addStep(Caller.member(ownerId), workflow.getId(),
                        new NewStep(null, "echo", null, null));
            };
            List<Future<WorkflowStepView>> results = new ArrayList<>();
            for (int i = 0; i < APPENDS; i++) {
                results.add(pool.submit(append));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (Future<WorkflowStepView> result : results) {
                result.get(30, TimeUnit.SECONDS);
            }

            assertThat(workflowStepRepository.findByWorkflowIdOrderByStepOrderAsc(workflow.getId()))
                    .extracting(WorkflowStep::getStepOrder)
                    .containsExactly((short) 1, (short) 2, (short) 3, (short) 4, (short) 5, (short) 6, (short) 7,
                            (short) 8);
        } finally {
            pool.shutdownNow();
        }
    }
}
