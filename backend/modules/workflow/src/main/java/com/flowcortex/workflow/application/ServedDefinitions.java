package com.flowcortex.workflow.application;

import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowStep;
import com.flowcortex.workflow.domain.WorkflowVersion;
import com.flowcortex.workflow.infrastructure.WorkflowStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowVersionRepository;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Resolves the steps a caller is served for workflows: the live draft, the caller's pinned version or the published
 * version, by the rule table of {@link ServedDefinition}. A page of workflows costs one pin query and one version
 * query, not one per workflow.
 */
@Component
class ServedDefinitions {

    /** The served steps and the number of the version they come from, null when they are the live ones. */
    record ServedSteps(List<WorkflowStep> steps, Integer version) {
    }

    private final WorkflowStepRepository workflowStepRepository;
    private final WorkflowVersionRepository versionRepository;

    ServedDefinitions(WorkflowStepRepository workflowStepRepository, WorkflowVersionRepository versionRepository) {
        this.workflowStepRepository = workflowStepRepository;
        this.versionRepository = versionRepository;
    }

    List<WorkflowStep> stepsFor(Workflow workflow, UUID callerId) {
        return servedFor(List.of(workflow), callerId).get(workflow.getId()).steps();
    }

    /** One entry per workflow, keyed by workflow id. */
    Map<UUID, ServedSteps> servedFor(List<Workflow> workflows, UUID callerId) {
        List<Workflow> others = workflows.stream()
                .filter(workflow -> !workflow.isSystemOwned() && !workflow.getOwnerId().equals(callerId))
                .toList();
        Map<UUID, WorkflowVersion> pinned = new HashMap<>();
        Map<UUID, WorkflowVersion> pointed = new HashMap<>();
        if (!others.isEmpty()) {
            List<UUID> otherIds = others.stream().map(Workflow::getId).toList();
            versionRepository.findPinnedBy(callerId, otherIds)
                    .forEach(version -> pinned.put(version.getWorkflowId(), version));
            Set<UUID> pointerIds = new HashSet<>();
            others.stream().map(Workflow::getPublishedVersionId).filter(id -> id != null).forEach(pointerIds::add);
            if (!pointerIds.isEmpty()) {
                versionRepository.findByIdIn(pointerIds).forEach(version -> pointed.put(version.getId(), version));
            }
        }

        Map<UUID, ServedSteps> served = new LinkedHashMap<>();
        for (Workflow workflow : workflows) {
            WorkflowVersion pin = pinned.get(workflow.getId());
            ServedDefinition.Facts facts = new ServedDefinition.Facts(workflow.isSystemOwned(),
                    workflow.getOwnerId().equals(callerId), pin == null ? null : pin.getVersion(),
                    workflow.getPublishedVersionId());
            served.put(workflow.getId(), switch (ServedDefinition.resolve(facts)) {
                case ServedDefinition.Live live -> new ServedSteps(
                        workflowStepRepository.findByWorkflowIdOrderByStepOrderAsc(workflow.getId()), null);
                case ServedDefinition.Pinned ignored -> fromVersion(workflow, pin);
                case ServedDefinition.Pointer pointer -> fromVersion(workflow, pointed.get(pointer.versionId()));
            });
        }
        return served;
    }

    private static ServedSteps fromVersion(Workflow workflow, WorkflowVersion version) {
        if (version == null) {
            throw new IllegalStateException("workflow " + workflow.getId() + " points at a version that is gone");
        }
        return new ServedSteps(
                WorkflowSnapshots.stepsOf(WorkflowSnapshots.parse(version.getSnapshot()), workflow.getId()),
                version.getVersion());
    }
}
