package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.workflow.api.ProductVideoWorkflow;
import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.flyway.locations=filesystem:${flowcortex.migrations.dir}",
        "spring.flyway.postgresql.transactional-lock=false",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class ProductVideoParallelReaperIT {

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired
    private WorkflowRunRepository runRepository;

    @Autowired
    private RunStepRepository stepRepository;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Test
    void findsProductVideoRunWithOneRunningClipAndPendingSibling() {
        UUID halfDispatchedId = seedProductVideoRun(true, false, "{step:3}", "{step:3}");
        UUID bothRunningId = seedProductVideoRun(true, true, "{step:3}", "{step:3}");
        UUID nonMatchingRefId = seedProductVideoRun(true, false, "{step:3}", "{step:2}");
        UUID otherWorkflowId = seedOtherWorkflowRun();

        List<UUID> standard = runRepository.findStalledRunIds(PageRequest.of(0, 100));
        List<UUID> parallel = runRepository.findStalledRunIdsWithParallelClips(PageRequest.of(0, 100));

        assertThat(standard).doesNotContain(halfDispatchedId, bothRunningId, nonMatchingRefId, otherWorkflowId);
        assertThat(parallel).contains(halfDispatchedId);
        assertThat(parallel).doesNotContain(bothRunningId, nonMatchingRefId, otherWorkflowId);
    }

    private UUID seedProductVideoRun(boolean clip5Running, boolean clip6Running, String ref5, String ref6) {
        UUID runId = UUID.randomUUID();
        runRepository.saveAndFlush(new WorkflowRun(runId, ProductVideoWorkflow.ID, UUID.randomUUID(), "{}", NOW));
        stepRepository.saveAllAndFlush(List.of(
                completed(runId, 1, "generate_script", null),
                completed(runId, 2, "APPROVAL_CHECKPOINT", null),
                completed(runId, 3, "generate_storyboard", "{step:2}"),
                completed(runId, 4, "generate_speech", "{step:2.script}"),
                clipStep(runId, 5, clip5Running, ref5),
                clipStep(runId, 6, clip6Running, ref6),
                new RunStep(UUID.randomUUID(), runId, (short) 7, "render_video")));
        return runId;
    }

    private UUID seedOtherWorkflowRun() {
        Workflow workflow = workflowRepository.saveAndFlush(
                new Workflow(UUID.randomUUID(), UUID.randomUUID(), "other-" + UUID.randomUUID(), false));
        UUID runId = UUID.randomUUID();
        runRepository.saveAndFlush(new WorkflowRun(runId, workflow.getId(), UUID.randomUUID(), "{}", NOW));
        stepRepository.saveAllAndFlush(List.of(
                completed(runId, 1, "generate_script", null),
                completed(runId, 2, "APPROVAL_CHECKPOINT", null),
                completed(runId, 3, "generate_storyboard", "{step:2}"),
                completed(runId, 4, "generate_speech", "{step:2}"),
                clipStep(runId, 5, true, "{step:3}"),
                clipStep(runId, 6, false, "{step:3}")));
        return runId;
    }

    private static RunStep completed(UUID runId, int order, String type, String ref) {
        RunStep step = new RunStep(UUID.randomUUID(), runId, (short) order, type, null, null, ref);
        step.markRunning(NOW);
        step.markCompleted(null, "{}", NOW);
        return step;
    }

    private static RunStep clipStep(UUID runId, int order, boolean running, String ref) {
        RunStep step = new RunStep(UUID.randomUUID(), runId, (short) order, "generate_video", null, null, ref);
        if (running) {
            step.markRunning(NOW);
        }
        return step;
    }
}
