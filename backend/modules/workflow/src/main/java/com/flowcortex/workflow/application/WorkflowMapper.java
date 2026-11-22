package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.workflow.api.AdminRunDetailView;
import com.flowcortex.workflow.api.AdminRunStepView;
import com.flowcortex.workflow.api.AdminRunView;
import com.flowcortex.workflow.api.MediaJobView;
import com.flowcortex.workflow.api.RunStepChildView;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** Entity -> {@code api} view mapping, mirroring {@code AppUserMapper}'s role in {@code :modules:user}. */
final class WorkflowMapper {

    /** Reads each stored output; the run view shows only name, mime and bytes of it. */
    private static final ObjectMapper OUTPUTS_READER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private WorkflowMapper() {
    }

    static WorkflowView toWorkflowView(Workflow workflow, List<WorkflowStep> steps, Predicate<String> isRemoteType,
            Predicate<String> isAvailableType) {
        return toWorkflowView(workflow, steps, isRemoteType, isAvailableType, null, null);
    }

    static WorkflowView toWorkflowView(Workflow workflow, List<WorkflowStep> steps, Predicate<String> isRemoteType,
            Predicate<String> isAvailableType, UUID publishedVersionId, Integer servedVersion) {
        return new WorkflowView(workflow.getId(), workflow.getOwnerId(), workflow.getName(), workflow.getVisibility(),
                workflow.getCreatedAt(), workflow.getUpdatedAt(),
                steps.stream().map(step -> toStepView(step, isRemoteType)).toList(),
                steps.stream().map(WorkflowStep::getType).allMatch(isAvailableType),
                wire(workflow.getStatus().name()), publishedVersionId, servedVersion);
    }

    /** The same view with every step reduced to what a reader who does not own the workflow may see. */
    static WorkflowView withoutStepContent(WorkflowView view) {
        return new WorkflowView(view.id(), view.ownerId(), view.name(), view.visibility(), view.createdAt(),
                view.updatedAt(),
                view.steps().stream()
                        .map(step -> new WorkflowStepView(step.id(), step.stepOrder(), step.name(), step.type(),
                                step.mode(), null, null))
                        .toList(),
                view.available(), view.status(), view.publishedVersionId(), view.servedVersion());
    }

    static WorkflowStepView toStepView(WorkflowStep step, Predicate<String> isRemoteType) {
        return new WorkflowStepView(step.getId(), step.getStepOrder(), step.getName(), step.getType(),
                mode(step.getType(), isRemoteType), step.getInputRef(), step.getConfig());
    }

    static String mode(String type, Predicate<String> isRemoteType) {
        return isRemoteType.test(type) ? "remote" : "inline";
    }

    /**
     * A remote step's input is a reference to where its object is stored, which never leaves the
     * server; it is shown as {@code null}. The run's own input is shown: when the first step is
     * remote it is only the cleaned name of the uploaded file. A step offers only the outputs stored
     * in a bucket {@code isDownloadable} accepts. {@code children} are the items of the run's iterate steps in
     * the attempt each step is in now; each step shows those whose parent it is, in the order given.
     */
    static RunView toRunView(WorkflowRun run, List<RunStep> steps, List<RunStep> children,
            Predicate<String> isRemoteType, Predicate<String> isDownloadable) {
        Map<UUID, List<RunStep>> itemsByParent = children.stream()
                .collect(Collectors.groupingBy(RunStep::getParentStepId, LinkedHashMap::new, Collectors.toList()));
        return new RunView(run.getId(), run.getWorkflowId(), run.getOwnerId(), wire(run.getStatus().name()),
                run.getInput(), run.getErrorMessage(), run.getStartedAt(),
                run.getFinishedAt(),
                steps.stream()
                        .map(step -> toRunStepView(step, itemsByParent.getOrDefault(step.getId(), List.of()),
                                isRemoteType, isDownloadable))
                        .toList());
    }

    private static RunStepView toRunStepView(RunStep step, List<RunStep> children, Predicate<String> isRemoteType,
            Predicate<String> isDownloadable) {
        Short percent = step.getProgressPercent();
        return new RunStepView(step.getId(), step.getStepOrder(), step.getType(), wire(step.getStatus().name()),
                isRemoteType.test(step.getType()) ? null : step.getInput(), step.getOutput(), step.getErrorMessage(),
                step.getStartedAt(), step.getFinishedAt(), percent == null ? null : percent.intValue(),
                step.getAttempt(), step.getErrorCode(), step.getDeliveryCount(), step.getPickedUpAt(),
                outputs(step, isDownloadable), mode(step.getType(), isRemoteType),
                children.stream().map(child -> toRunStepChildView(child, isDownloadable)).toList());
    }

    private static RunStepChildView toRunStepChildView(RunStep child, Predicate<String> isDownloadable) {
        return new RunStepChildView(child.getId(), child.getItemIndex(), wire(child.getStatus().name()),
                child.getOutput(), child.getErrorCode(), child.getErrorMessage(), child.getAttempt(),
                child.getStartedAt(), child.getFinishedAt(), outputs(child, isDownloadable));
    }

    static AdminRunView toAdminRunView(WorkflowRun run, String workflowName, String ownerEmail,
            Long creditsCharged) {
        return new AdminRunView(run.getId(), run.getWorkflowId(), workflowName, run.getOwnerId(), ownerEmail,
                wire(run.getStatus().name()), run.getErrorMessage(), run.getStartedAt(), run.getFinishedAt(),
                creditsCharged);
    }

    static AdminRunDetailView toAdminRunDetailView(WorkflowRun run, String workflowName, String ownerEmail,
            Long creditsCharged, List<RunStep> steps, Predicate<String> isRemoteType) {
        return new AdminRunDetailView(run.getId(), run.getWorkflowId(), workflowName, run.getOwnerId(), ownerEmail,
                wire(run.getStatus().name()), run.getErrorMessage(), run.getStartedAt(), run.getFinishedAt(),
                creditsCharged, steps.stream().map(step -> toAdminRunStepView(step, isRemoteType)).toList());
    }

    private static AdminRunStepView toAdminRunStepView(RunStep step, Predicate<String> isRemoteType) {
        Short percent = step.getProgressPercent();
        return new AdminRunStepView(step.getStepOrder(), step.getType(), mode(step.getType(), isRemoteType),
                wire(step.getStatus().name()), step.getAttempt(), step.getDeliveryCount(),
                percent == null ? null : percent.intValue(), step.getErrorCode(), step.getErrorMessage(),
                step.getPickedUpAt(), step.getStartedAt(), step.getFinishedAt());
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
        return downloadableOutputs(step, isDownloadable).stream()
                .map(output -> new RunStepOutputView(output.name(), output.mime(), output.bytes()))
                .toList();
    }

    /** The outputs recorded on a step that sit in a bucket {@code isDownloadable} accepts. */
    static List<StoredOutput> downloadableOutputs(RunStep step, Predicate<String> isDownloadable) {
        if (step.getOutputs() == null) {
            return List.of();
        }
        try {
            return Arrays.stream(OUTPUTS_READER.readValue(step.getOutputs(), StoredOutput[].class))
                    .filter(output -> isDownloadable.test(output.bucket()))
                    .toList();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("run_step " + step.getId() + " holds outputs that are not an output list", e);
        }
    }

    /** A recorded output; the bucket decides whether it is offered. */
    record StoredOutput(String name, String bucket, String objectKey, String mime, long bytes) {
    }

    /** run/run_step status is wire-visible lowercase (running/completed/error/pending). */
    private static String wire(String enumName) {
        return enumName.toLowerCase(Locale.ROOT);
    }
}
