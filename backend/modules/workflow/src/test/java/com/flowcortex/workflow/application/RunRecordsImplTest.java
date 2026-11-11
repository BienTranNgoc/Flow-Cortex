package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flowcortex.workflow.api.RunRecord;
import com.flowcortex.workflow.api.RunRecordStep;
import com.flowcortex.workflow.infrastructure.RunRecordQueries;
import com.flowcortex.workflow.infrastructure.RunRecordQueries.RunRow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RunRecordsImplTest {

    private static final Instant FINISHED = Instant.parse("2026-11-10T10:00:00Z");

    private final RunRecordQueries queries = mock(RunRecordQueries.class);
    private final StepExecutorRegistry registry = mock(StepExecutorRegistry.class);
    private final RunRecordsImpl records = new RunRecordsImpl(queries, registry);

    private final UUID runId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();
    private final UUID workflowId = UUID.randomUUID();

    private RunRow row(String input, String productName) {
        return new RunRow(runId, ownerId, workflowId, "Flow", "completed", input, productName, FINISHED);
    }

    private List<RunRecordStep> steps(String... types) {
        List<RunRecordStep> steps = new ArrayList<>();
        for (int i = 0; i < types.length; i++) {
            steps.add(new RunRecordStep(UUID.randomUUID(), i + 1, types[i], (short) 1));
        }
        return steps;
    }

    @Test
    void aProductVideoRunIsTitledByItsProductNameAndHasNoFileName() {
        when(queries.findRun(runId)).thenReturn(Optional.of(row("{\"productName\":\"Shoe\"}", "Shoe")));
        when(queries.findSteps(runId)).thenReturn(steps("probe"));
        when(registry.isRemote("probe")).thenReturn(true);

        RunRecord record = records.find(runId).orElseThrow();

        assertThat(record.titleInput()).isEqualTo("Shoe");
        assertThat(record.inputFileName()).isNull();
        assertThat(record.ownerId()).isEqualTo(ownerId);
        assertThat(record.workflowName()).isEqualTo("Flow");
        assertThat(record.status()).isEqualTo("completed");
        assertThat(record.finishedAt()).isEqualTo(FINISHED);
        assertThat(record.steps()).hasSize(1);
    }

    @Test
    void aRunWhoseFirstStepIsRemoteHasItsInputAsTitleAndFileName() {
        when(queries.findRun(runId)).thenReturn(Optional.of(row("clip.mp4", null)));
        when(queries.findSteps(runId)).thenReturn(steps("probe", "tts"));
        when(registry.isRemote("probe")).thenReturn(true);

        RunRecord record = records.find(runId).orElseThrow();

        assertThat(record.titleInput()).isEqualTo("clip.mp4");
        assertThat(record.inputFileName()).isEqualTo("clip.mp4");
    }

    @Test
    void aTextRunHasItsInputAsTitleAndNoFileName() {
        when(queries.findRun(runId)).thenReturn(Optional.of(row("write a poem", null)));
        when(queries.findSteps(runId)).thenReturn(steps("llm", "probe"));
        when(registry.isRemote("llm")).thenReturn(false);

        RunRecord record = records.find(runId).orElseThrow();

        assertThat(record.titleInput()).isEqualTo("write a poem");
        assertThat(record.inputFileName()).isNull();
    }

    @Test
    void aMissingRunIsEmpty() {
        when(queries.findRun(runId)).thenReturn(Optional.empty());

        assertThat(records.find(runId)).isEmpty();
    }
}
