package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.workflow.api.NewStep;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowStep;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import com.flowcortex.workflow.infrastructure.WorkflowStepRepository;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
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
 * Delete and reorder go through the production service on a real Postgres: {@code uq_workflow_step_order} is not
 * deferrable, so the native order writes must never collide and must never leave a negative order behind.
 * {@code NOT_SUPPORTED} so each seeded workflow is committed before the service runs.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({WorkflowServiceImpl.class, WorkflowStepRenumberIT.Beans.class})
@Testcontainers
class WorkflowStepRenumberIT {

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
    private WorkflowAuthoring authoring;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private WorkflowStepRepository workflowStepRepository;

    private Workflow seed(UUID ownerId) {
        return workflowRepository.saveAndFlush(Workflow.draft(UUID.randomUUID(), ownerId, "Renumber", false));
    }

    private WorkflowStep step(Workflow workflow, int order, String name, String inputRef, String config) {
        return workflowStepRepository.saveAndFlush(new WorkflowStep(UUID.randomUUID(), workflow.getId(),
                (short) order, name, "echo", inputRef, config));
    }

    private List<WorkflowStep> rows(Workflow workflow) {
        return workflowStepRepository.findByWorkflowIdOrderByStepOrderAsc(workflow.getId());
    }

    @Test
    void reversingThreeStepsRenumbersWithoutAUniqueViolation() {
        UUID owner = UUID.randomUUID();
        Workflow workflow = seed(owner);
        WorkflowStep a = step(workflow, 1, "A", null, null);
        WorkflowStep b = step(workflow, 2, "B", null, null);
        WorkflowStep c = step(workflow, 3, "C", null, null);

        authoring.reorderSteps(Caller.member(owner), workflow.getId(), List.of(c.getId(), b.getId(), a.getId()));

        List<WorkflowStep> stored = rows(workflow);
        assertThat(stored).extracting(WorkflowStep::getName).containsExactly("C", "B", "A");
        assertThat(stored).extracting(WorkflowStep::getStepOrder).containsExactly((short) 1, (short) 2, (short) 3);
    }

    @Test
    void deletingTheFirstOfThreeStepsLeavesOrdersOneAndTwo() {
        UUID owner = UUID.randomUUID();
        Workflow workflow = seed(owner);
        WorkflowStep a = step(workflow, 1, "A", null, null);
        step(workflow, 2, "B", null, null);
        step(workflow, 3, "C", "{step:2.output}", null);

        authoring.deleteStep(Caller.member(owner), workflow.getId(), a.getId());

        List<WorkflowStep> stored = rows(workflow);
        assertThat(stored).extracting(WorkflowStep::getName).containsExactly("B", "C");
        assertThat(stored).extracting(WorkflowStep::getStepOrder).containsExactly((short) 1, (short) 2);
        assertThat(stored).extracting(WorkflowStep::getInputRef).containsExactly(null, "{step:1.output}");
    }

    @Test
    void reorderingStepsWithNullConfigAndNullInputRefWritesOnlyOrders() {
        UUID owner = UUID.randomUUID();
        Workflow workflow = seed(owner);
        WorkflowStep a = step(workflow, 1, "A", null, null);
        WorkflowStep b = step(workflow, 2, "B", null, null);

        authoring.reorderSteps(Caller.member(owner), workflow.getId(), List.of(b.getId(), a.getId()));

        List<WorkflowStep> stored = rows(workflow);
        assertThat(stored).extracting(WorkflowStep::getName).containsExactly("B", "A");
        assertThat(stored).extracting(WorkflowStep::getConfig).containsOnlyNulls();
        assertThat(stored).extracting(WorkflowStep::getInputRef).containsOnlyNulls();
    }

    @Test
    void aDeleteBesideAnAppendLeavesGapFreeOrders() throws Exception {
        UUID owner = UUID.randomUUID();
        Workflow workflow = seed(owner);
        WorkflowStep a = step(workflow, 1, "A", null, null);
        step(workflow, 2, "B", null, null);
        step(workflow, 3, "C", null, null);
        step(workflow, 4, "D", null, null);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> delete = pool.submit(() -> {
                ready.countDown();
                go.await();
                authoring.deleteStep(Caller.member(owner), workflow.getId(), a.getId());
                return null;
            });
            Future<?> append = pool.submit(() -> {
                ready.countDown();
                go.await();
                return authoring.addStep(Caller.member(owner), workflow.getId(), new NewStep("E", "echo", null, null));
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            delete.get(30, TimeUnit.SECONDS);
            append.get(30, TimeUnit.SECONDS);

            assertThat(rows(workflow)).extracting(WorkflowStep::getStepOrder)
                    .containsExactly((short) 1, (short) 2, (short) 3, (short) 4);
        } finally {
            pool.shutdownNow();
        }
    }
}
