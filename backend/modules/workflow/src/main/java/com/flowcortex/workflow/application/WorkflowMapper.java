package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.workflow.api.AdminRunView;
import com.flowcortex.workflow.api.MediaJobView;
import com.flowcortex.workflow.api.RunStepOutputView;
import com.flowcortex.workflow.api.RunStepView;
import com.flowcortex.workflow.api.RunView;
import com.flowcortex.workflow.api.WorkflowStepView;
import com.flowcortex.workflow.api.WorkflowView;
import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.domain.WorkflowStep;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/** Entity -> {@code api} view mapping, mirroring {@code AppUserMapper}'s role in {@code :modules:user}. */
final class WorkflowMapper {

    /** Reads name, bucket, mime and bytes of each stored output; only name, mime and bytes reach the view. */
    private static final ObjectMapper OUTPUTS_READER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private WorkflowMapper() {
    }

    static WorkflowView toWorkflowView(Workflow workflow, List<WorkflowStep> steps, Predicate<String> isRemoteType,
            Predicate<String> isAvailableType) {
        return new WorkflowView(workflow.getId(), workflow.getOwnerId(), workflow.getName(), workflow.isShared(),
                workflow.getCreatedAt(), workflow.getUpdatedAt(),
                steps.stream().map(step -> toStepView(step, isRemoteType)).toList(),
                steps.stream().map(WorkflowStep::getType).allMatch(isAvailableType));
    }

    private static WorkflowStepView toStepView(WorkflowStep step, Predicate<String> isRemoteType) {
        return new WorkflowStepView(step.getId(), step.getStepOrder(), step.getName(), step.getType(),
                mode(step.getType(), isRemoteType));
    }

    private static String mode(String type, Predicate<String> isRemoteType) {
        return isRemoteType.test(type) ? "remote" : "inline";
    }

    /**
     * A remote step's input is a reference to where its object is stored, which never leaves the
     * server; it is shown as {@code null}. The run's own input is shown: when the first step is
     * remote it is only the cleaned name of the uploaded file. A step offers only the outputs stored
     * in a bucket {@code isDownloadable} accepts.
     */
    static RunView toRunView(WorkflowRun run, List<RunStep> steps, Predicate<String> isRemoteType,
            Predicate<String> isDownloadable) {
        return new RunView(run.getId(), run.getWorkflowId(), run.getOwnerId(), wire(run.getStatus().name()),
                run.getInput(), run.getErrorMessage(), run.getStartedAt(),
                run.getFinishedAt(),
                steps.stream().map(step -> toRunStepView(step, isRemoteType, isDownloadable)).toList());
    }

    private static RunStepView toRunStepView(RunStep step, Predicate<String> isRemoteType,
            Predicate<String> isDownloadable) {
        Short percent = step.getProgressPercent();
        return new RunStepView(step.getId(), step.getStepOrder(), step.getType(), wire(step.getStatus().name()),
                isRemoteType.test(step.getType()) ? null : step.getInput(), step.getOutput(), step.getErrorMessage(),
                step.getStartedAt(), step.getFinishedAt(), percent == null ? null : percent.intValue(),
                step.getAttempt(), step.getErrorCode(), step.getDeliveryCount(), step.getPickedUpAt(),
                outputs(step, isDownloadable), mode(step.getType(), isRemoteType));
    }

    static AdminRunView toAdminRunView(WorkflowRun run, String workflowName, String ownerEmail) {
        return new AdminRunView(run.getId(), workflowName, ownerEmail, wire(run.getStatus().name()),
                run.getErrorMessage(), run.getStartedAt(), run.getFinishedAt());
    }

    static MediaJobView toMediaJobView(RunStep step) {
        Short percent = step.getProgressPercent();
        return new MediaJobView(step.getId(), step.getRunId(), step.getStepOrder(), step.getType(),
                wire(step.getStatus().name()), step.getAttempt(), step.getDeliveryCount(), step.getPickedUpAt(),
                step.getQueueDeadline(), step.getLeaseUntil(), step.getRunDeadline(),
                percent == null ? null : percent.intValue(), step.getErrorCode(), step.getErrorMessage(),
                step.getStartedAt(), step.getFinishedAt());
    }

    private static List<RunStepOutputView> outputs(RunStep step, Predicate<String> isDownloadable) {
        if (step.getOutputs() == null) {
            return List.of();
        }
        try {
            return Arrays.stream(OUTPUTS_READER.readValue(step.getOutputs(), StoredOutput[].class))
                    .filter(output -> isDownloadable.test(output.bucket()))
                    .map(output -> new RunStepOutputView(output.name(), output.mime(), output.bytes()))
                    .toList();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("run_step " + step.getId() + " holds outputs that are not an output list", e);
        }
    }

    /** A recorded output as far as the run view reads it; the bucket decides whether it is offered. */
    private record StoredOutput(String name, String bucket, String mime, long bytes) {
    }

    /** run/run_step status is wire-visible lowercase (running/completed/error/pending). */
    private static String wire(String enumName) {
        return enumName.toLowerCase(Locale.ROOT);
    }
}
