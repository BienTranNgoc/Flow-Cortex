package com.flowcortex.workflow.application;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowStatus;
import com.flowcortex.workflow.domain.WorkflowStep;
import com.flowcortex.workflow.domain.WorkflowVersion;
import com.flowcortex.workflow.domain.WorkflowVersionSource;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import com.flowcortex.workflow.infrastructure.WorkflowStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowVersionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Appends versions and moves the published pointer. Every method must be called with the workflow row locked by
 * {@link WorkflowWriteAccess#lockForWrite}: the lock is what makes "read the newest, compare, insert the next
 * number" atomic.
 */
@Component
class VersionWriter {

    /** The version a write ended on; {@code deduplicated} when the definition equalled the newest and nothing was added. */
    record Appended(WorkflowVersion version, boolean deduplicated) {
    }

    private final WorkflowVersionRepository versionRepository;
    private final WorkflowRepository workflowRepository;
    private final WorkflowStepRepository workflowStepRepository;

    VersionWriter(WorkflowVersionRepository versionRepository, WorkflowRepository workflowRepository,
            WorkflowStepRepository workflowStepRepository) {
        this.versionRepository = versionRepository;
        this.workflowRepository = workflowRepository;
        this.workflowStepRepository = workflowStepRepository;
    }

    Appended append(Workflow locked, List<WorkflowStep> live, WorkflowVersionSource source, String changelog,
            Integer restoredFrom, UUID publisher) {
        ObjectNode snapshot = WorkflowSnapshots.of(locked, live);
        Optional<WorkflowVersion> newest = versionRepository.findTopByWorkflowIdOrderByVersionDesc(locked.getId());
        if (newest.isPresent()
                && WorkflowSnapshots.sameDefinition(WorkflowSnapshots.parse(newest.get().getSnapshot()), snapshot)) {
            return new Appended(newest.get(), true);
        }
        int next = newest.map(version -> version.getVersion() + 1).orElse(1);
        WorkflowVersion saved = versionRepository.save(new WorkflowVersion(UUID.randomUUID(), locked.getId(), next,
                changelog, source, restoredFrom, snapshot.toString(), publisher, Instant.now()));
        return new Appended(saved, false);
    }

    /**
     * Gives a workflow that has just become visible to other members, published and is not yet pointed anywhere a
     * pointer on its live definition; any other state changes nothing.
     */
    void ensurePublishedPointer(Workflow locked, UUID publisher) {
        if (!servesOtherMembers(locked) || locked.isSystemOwned() || locked.getPublishedVersionId() != null) {
            return;
        }
        List<WorkflowStep> live = workflowStepRepository.findByWorkflowIdOrderByStepOrderAsc(locked.getId());
        pointAndRelease(locked, append(locked, live, WorkflowVersionSource.PUBLISH, null, null, publisher).version());
    }

    void pointAndRelease(Workflow locked, WorkflowVersion version) {
        locked.pointTo(version.getId());
        version.release(Instant.now());
        versionRepository.save(version);
        workflowRepository.save(locked);
    }

    /** The single place that says who the published definition is for. */
    private static boolean servesOtherMembers(Workflow workflow) {
        return workflow.isShared() && workflow.getStatus() == WorkflowStatus.PUBLISHED;
    }
}
