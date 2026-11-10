package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.workflow.api.RunResultView;
import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import com.flowcortex.workflow.infrastructure.RunWithWorkflowName;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

/** What the member-result read port returns for completed runs: shape, scoping and the output filter. */
class RunResultsImplTest {

    private static final Instant STARTED = Instant.parse("2026-11-01T10:00:00Z");
    private static final Instant FINISHED = Instant.parse("2026-11-01T10:05:00Z");
    private static final String BUCKET = "flowcortex-media";

    private final WorkflowRunRepository workflowRunRepository = mock(WorkflowRunRepository.class);
    private final RunStepRepository runStepRepository = mock(RunStepRepository.class);
    private final MediaJobs mediaJobs = mock(MediaJobs.class);
    private final RunResultsImpl results = new RunResultsImpl(workflowRunRepository, runStepRepository,
            new StepExecutorRegistry(mediaJobs, Clock.systemUTC()), mediaJobs);

    private final UUID ownerId = UUID.randomUUID();

    RunResultsImplTest() {
        when(mediaJobs.isDownloadable(BUCKET)).thenReturn(true);
    }

    @Test
    void aRunThatIsNotCompletedHasNoResult() {
        UUID runId = UUID.randomUUID();
        when(workflowRunRepository.findCompletedWithWorkflowName(runId)).thenReturn(Optional.empty());

        assertThat(results.completedRun(runId)).isEmpty();

        verify(runStepRepository, never()).findByRunIdOrderByStepOrderAsc(any());
    }

    @Test
    void textInputIsNullWhenTheFirstStepIsRemote() {
        CompletedRun remoteFirst = completedRun("clean-name.mp4", "probe", "echo");
        CompletedRun inlineFirst = completedRun("hello", "echo", "echo");

        assertThat(results.completedRun(remoteFirst.run().getId()).orElseThrow().textInput()).isNull();
        RunResultView inline = results.completedRun(inlineFirst.run().getId()).orElseThrow();
        assertThat(inline.textInput()).isEqualTo("hello");
        assertThat(inline.workflowName()).isEqualTo("Demo");
        assertThat(inline.finishedAt()).isEqualTo(FINISHED);
        assertThat(inline.steps()).extracting(RunResultView.Step::mode).containsExactly("inline", "inline");
        assertThat(inline.steps()).extracting(RunResultView.Step::status).containsOnly("completed");
    }

    @Test
    void onlyDownloadableOutputsAreListedWithBucketAndKey() {
        UUID runId = UUID.randomUUID();
        String below = "runs/" + runId + "/steps/1/out.png";
        String outputs = "[{\"name\":\"out.png\",\"bucket\":\"" + BUCKET + "\",\"objectKey\":\"" + below
                + "\",\"mime\":\"image/png\",\"bytes\":42},"
                + "{\"name\":\"mid.png\",\"bucket\":\"flowcortex-uploads\",\"objectKey\":\"runs/" + runId
                + "/steps/1/mid.png\",\"mime\":\"image/png\",\"bytes\":7},"
                + "{\"name\":\"stray.png\",\"bucket\":\"" + BUCKET + "\",\"objectKey\":\"runs/" + runId
                + "/steps/2/stray.png\",\"mime\":\"image/png\",\"bytes\":9},"
                + "{\"name\":\"nokey.png\",\"bucket\":\"" + BUCKET + "\",\"mime\":\"image/png\",\"bytes\":9}]";
        WorkflowRun run = completed(runId, "clean.mp4");
        RunStep step = completedStep(runId, 1, "probe", "{}", outputs);
        when(workflowRunRepository.findCompletedWithWorkflowName(runId))
                .thenReturn(Optional.of(new RunWithWorkflowName(run, "Demo")));
        when(runStepRepository.findByRunIdOrderByStepOrderAsc(runId)).thenReturn(List.of(step));

        List<RunResultView.Output> listed = results.completedRun(runId).orElseThrow().steps().getFirst().outputs();

        assertThat(listed).containsExactly(new RunResultView.Output("out.png", BUCKET, below, "image/png", 42));
    }

    @Test
    void recentCompletedRunsAreNewestFinishedFirstAndOwnerScoped() {
        CompletedRun newer = completedRun("b", "echo");
        CompletedRun older = completedRun("a", "echo");
        when(workflowRunRepository.findCompletedByOwnerNewestFirst(ownerId, PageRequest.of(0, 2)))
                .thenReturn(List.of(newer.withName(), older.withName()));

        List<RunResultView> recent = results.recentCompletedRuns(ownerId, 2);

        assertThat(recent).extracting(RunResultView::runId)
                .containsExactly(newer.run().getId(), older.run().getId());
        assertThat(recent).extracting(RunResultView::ownerId).containsOnly(ownerId);
        assertThatThrownBy(() -> results.recentCompletedRuns(ownerId, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stepOutputOfAnotherOwnersRunIsEmpty() {
        CompletedRun mine = completedRun("x", "echo", "echo");
        UUID stranger = UUID.randomUUID();

        assertThat(results.stepOutput(ownerId, mine.run().getId(), 2)).contains("out-2");
        assertThat(results.stepOutput(stranger, mine.run().getId(), 2)).isEmpty();
        assertThat(results.stepOutput(ownerId, mine.run().getId(), 9)).isEmpty();
    }

    private CompletedRun completedRun(String input, String... types) {
        UUID runId = UUID.randomUUID();
        WorkflowRun run = completed(runId, input);
        List<RunStep> steps = new ArrayList<>();
        for (int i = 0; i < types.length; i++) {
            steps.add(completedStep(runId, i + 1, types[i], "out-" + (i + 1), null));
        }
        when(workflowRunRepository.findCompletedWithWorkflowName(runId))
                .thenReturn(Optional.of(new RunWithWorkflowName(run, "Demo")));
        when(runStepRepository.findByRunIdOrderByStepOrderAsc(runId)).thenReturn(steps);
        return new CompletedRun(run);
    }

    private WorkflowRun completed(UUID runId, String input) {
        WorkflowRun run = new WorkflowRun(runId, UUID.randomUUID(), ownerId, input, STARTED);
        run.markCompleted(FINISHED);
        return run;
    }

    private static RunStep completedStep(UUID runId, int order, String type, String output, String outputs) {
        RunStep step = new RunStep(UUID.randomUUID(), runId, (short) order, type);
        step.markRunning(STARTED);
        step.markCompleted(null, output, FINISHED, outputs);
        return step;
    }

    private record CompletedRun(WorkflowRun run) {
        RunWithWorkflowName withName() {
            return new RunWithWorkflowName(run, "Demo");
        }
    }
}
