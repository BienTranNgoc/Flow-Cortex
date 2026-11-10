package com.flowcortex.workflow.application;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.workflow.api.PeaksView;
import com.flowcortex.workflow.api.RunDiagnostics;
import com.flowcortex.workflow.api.RunLogView;
import com.flowcortex.workflow.application.WorkflowRunServiceImpl.DownloadableOutput;
import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of {@link RunDiagnostics}. The waveform path holds no database transaction while it decodes:
 * the output is resolved by short reads, and the sound is streamed from the object store afterwards.
 */
@Service
class RunDiagnosticsImpl implements RunDiagnostics {

    private final WorkflowRunRepository runs;
    private final RunStepRepository steps;
    private final WorkflowRunServiceImpl runService;
    private final AudioPeaksService audioPeaks;

    RunDiagnosticsImpl(WorkflowRunRepository runs, RunStepRepository steps, WorkflowRunServiceImpl runService,
            AudioPeaksService audioPeaks) {
        this.runs = runs;
        this.steps = steps;
        this.runService = runService;
        this.audioPeaks = audioPeaks;
    }

    @Override
    @Transactional(readOnly = true)
    public RunLogView log(UUID currentUserId, UUID runId, String level, int limit) {
        if (!RunLogs.LEVELS.contains(level) || limit < 1) {
            throw new IllegalArgumentException("level " + level + " / limit " + limit + " is not a valid log request");
        }
        WorkflowRun run = runs.findByIdAndOwnerId(runId, currentUserId)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("workflow.run.notFound", runId)));
        List<RunStep> runSteps = steps.findByRunIdOrderByStepOrderAsc(runId);
        return RunLogs.project(run.getStatus().name().toLowerCase(Locale.ROOT), run.getTraceId(), runSteps, level,
                limit);
    }

    @Override
    public PeaksView peaks(UUID currentUserId, UUID runId, int stepOrder, String name) {
        DownloadableOutput output = runService.downloadableOutput(currentUserId, runId, stepOrder, name);
        if (output.mime() == null || !output.mime().toLowerCase(Locale.ROOT).startsWith("audio/")) {
            throw new LocalizedStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    new LocalizedMessage("workflow.step.outputNotAudio"));
        }
        return audioPeaks.peaks(output.runStepId(), output.attempt(), output.name(), output.bucket(),
                output.objectKey());
    }
}
