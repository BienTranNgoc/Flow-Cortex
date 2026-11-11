package com.flowcortex.workflow.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.workflow.api.DiffSide;
import com.flowcortex.workflow.api.InvalidVersionSideException;
import com.flowcortex.workflow.api.VersionDiffView;
import com.flowcortex.workflow.api.VersionPinView;
import com.flowcortex.workflow.api.VersionSavedView;
import com.flowcortex.workflow.api.VersionSnapshotView;
import com.flowcortex.workflow.api.WorkflowVersionDetailView;
import com.flowcortex.workflow.api.WorkflowVersionPageView;
import com.flowcortex.workflow.api.WorkflowVersionService;
import com.flowcortex.workflow.api.WorkflowVersionView;
import com.flowcortex.workflow.api.WorkflowView;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowStep;
import com.flowcortex.workflow.domain.WorkflowVersion;
import com.flowcortex.workflow.domain.WorkflowVersionPin;
import com.flowcortex.workflow.domain.WorkflowVersionPinId;
import com.flowcortex.workflow.domain.WorkflowVersionSource;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import com.flowcortex.workflow.infrastructure.WorkflowStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowVersionPinRepository;
import com.flowcortex.workflow.infrastructure.WorkflowVersionRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Implementation of {@link WorkflowVersionService}. */
@Service
class WorkflowVersionServiceImpl implements WorkflowVersionService {

    static final int MAX_PAGE_SIZE = 100;

    private static final Pattern VERSION_NUMBER = Pattern.compile("[1-9][0-9]{0,8}");
    private static final String DRAFT = "draft";
    private static final String PUBLISHED = "published";

    private final WorkflowRepository workflowRepository;
    private final WorkflowStepRepository workflowStepRepository;
    private final WorkflowVersionRepository versionRepository;
    private final WorkflowVersionPinRepository pinRepository;
    private final WorkflowWriteAccess writeAccess;
    private final VersionWriter versionWriter;
    private final StepExecutorRegistry registry;

    WorkflowVersionServiceImpl(WorkflowRepository workflowRepository, WorkflowStepRepository workflowStepRepository,
            WorkflowVersionRepository versionRepository, WorkflowVersionPinRepository pinRepository,
            WorkflowWriteAccess writeAccess, VersionWriter versionWriter, StepExecutorRegistry registry) {
        this.workflowRepository = workflowRepository;
        this.workflowStepRepository = workflowStepRepository;
        this.versionRepository = versionRepository;
        this.pinRepository = pinRepository;
        this.writeAccess = writeAccess;
        this.versionWriter = versionWriter;
        this.registry = registry;
    }

    @Override
    @Transactional(readOnly = true)
    public WorkflowVersionPageView list(UUID callerId, UUID workflowId, Integer beforeVersion, int limit) {
        Workflow workflow = visible(callerId, workflowId);
        boolean owner = isOwner(workflow, callerId);
        Pageable page = PageRequest.of(0, Math.clamp(limit, 1, MAX_PAGE_SIZE) + 1);
        List<WorkflowVersion> found;
        if (owner) {
            found = beforeVersion == null
                    ? versionRepository.findByWorkflowIdOrderByVersionDesc(workflowId, page)
                    : versionRepository.findByWorkflowIdAndVersionLessThanOrderByVersionDesc(workflowId,
                            beforeVersion, page);
        } else {
            found = beforeVersion == null
                    ? versionRepository.findByWorkflowIdAndReleasedAtIsNotNullOrderByVersionDesc(workflowId, page)
                    : versionRepository.findByWorkflowIdAndVersionLessThanAndReleasedAtIsNotNullOrderByVersionDesc(
                            workflowId, beforeVersion, page);
        }
        int size = page.getPageSize() - 1;
        boolean more = found.size() > size;
        List<WorkflowVersionView> items = found.stream().limit(size).map(version -> view(version, workflow)).toList();
        return new WorkflowVersionPageView(items, more ? items.getLast().version() : null);
    }

    @Override
    @Transactional(readOnly = true)
    public WorkflowVersionDetailView get(UUID callerId, UUID workflowId, int version) {
        Workflow workflow = visible(callerId, workflowId);
        boolean owner = isOwner(workflow, callerId);
        WorkflowVersion found = readable(workflow, owner, version)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("workflow.version.notFound", version)));
        return new WorkflowVersionDetailView(view(found, workflow), snapshotView(found, owner));
    }

    @Override
    @Transactional
    public VersionSavedView save(UUID callerId, UUID workflowId, String changelog) {
        return append(callerId, workflowId, WorkflowVersionSource.MANUAL, changelog);
    }

    @Override
    @Transactional
    public VersionSavedView hotfix(UUID callerId, UUID workflowId, String note) {
        return append(callerId, workflowId, WorkflowVersionSource.HOTFIX, note);
    }

    private VersionSavedView append(UUID callerId, UUID workflowId, WorkflowVersionSource source, String changelog) {
        Workflow workflow = writeAccess.lockForWrite(Caller.member(callerId), workflowId);
        VersionWriter.Appended appended = versionWriter.append(workflow, liveSteps(workflow), source,
                emptyToNull(changelog), null, callerId);
        return new VersionSavedView(view(appended.version(), workflow), appended.deduplicated());
    }

    @Override
    @Transactional
    public WorkflowVersionView rename(UUID callerId, UUID workflowId, int version, String changelog) {
        Workflow workflow = writeAccess.lockForWrite(Caller.member(callerId), workflowId);
        WorkflowVersion found = versionRepository.findByWorkflowIdAndVersion(workflowId, version)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("workflow.version.notFound", version)));
        if (!found.getSource().renameable()) {
            LocalizedMessage reason = new LocalizedMessage("workflow.version.notRenameable");
            throw new UnprocessableEntityException(reason, null, Map.of("source", reason));
        }
        found.rename(emptyToNull(changelog));
        return view(versionRepository.save(found), workflow);
    }

    @Override
    @Transactional
    public VersionSavedView rollback(UUID callerId, UUID workflowId, int version, String changelog) {
        Workflow locked = writeAccess.lockForWrite(Caller.member(callerId), workflowId);
        WorkflowVersion target = versionRepository.findByWorkflowIdAndVersion(workflowId, version)
                .orElseThrow(() -> refused("version"));
        JsonNode snapshot = WorkflowSnapshots.parse(target.getSnapshot());
        List<WorkflowStep> restored = WorkflowSnapshots.stepsOf(snapshot, workflowId);
        writeAccess.validateDefinition(WorkflowWriteAccess.definitionOf(restored));

        String name = snapshot.path("name").asText();
        if (!name.equals(locked.getName())) {
            locked.rename(name);
            workflowRepository.save(locked);
        }
        // The bulk delete clears the persistence context, so the workflow is read again under the same lock.
        workflowStepRepository.deleteAllByWorkflowId(workflowId);
        Workflow current = workflowRepository.findByIdAndDeletedAtIsNull(workflowId)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("workflow.notFound", workflowId)));
        List<WorkflowStep> saved = workflowStepRepository.saveAll(restored);

        VersionWriter.Appended appended = versionWriter.append(current, saved, WorkflowVersionSource.ROLLBACK,
                emptyToNull(changelog), version, callerId);
        return new VersionSavedView(view(appended.version(), current), appended.deduplicated());
    }

    @Override
    @Transactional(readOnly = true)
    public VersionDiffView diff(UUID callerId, UUID workflowId, String source, String target) {
        Workflow workflow = visible(callerId, workflowId);
        boolean owner = isOwner(workflow, callerId);
        Side from = parseSide("source", source, PUBLISHED);
        Side to = parseSide("target", target, DRAFT);
        if (!owner && (from.draft() || to.draft())) {
            throw new LocalizedStatusException(HttpStatus.FORBIDDEN,
                    new LocalizedMessage("workflow.version.draftOwnerOnly"));
        }
        Resolved before = resolve(workflow, owner, from);
        Resolved after = resolve(workflow, owner, to);

        VersionDiff diff = VersionDiff.between(before.snapshot(), after.snapshot());
        if (!owner) {
            diff = diff.skeleton();
        }
        return new VersionDiffView(before.side(), after.side(), !owner,
                new VersionDiffView.NameChange(diff.name().changed(), diff.name().before(), diff.name().after()),
                diff.steps().stream().map(WorkflowVersionServiceImpl::stepChange).toList());
    }

    @Override
    @Transactional
    public WorkflowView setPublishedVersion(UUID callerId, UUID workflowId, UUID versionId) {
        Workflow workflow = writeAccess.lockForWrite(Caller.member(callerId), workflowId);
        if (versionId == null) {
            throw refused("versionId");
        }
        WorkflowVersion target = versionRepository.findByIdAndWorkflowId(versionId, workflowId)
                .orElseThrow(() -> refused("versionId"));
        versionWriter.pointAndRelease(workflow, target);
        return WorkflowMapper.toWorkflowView(workflow, liveSteps(workflow), registry::isRemote,
                registry::isAvailable, workflow.getPublishedVersionId(), null);
    }

    @Override
    @Transactional(readOnly = true)
    public VersionPinView getPin(UUID callerId, UUID workflowId) {
        visible(callerId, workflowId);
        return pinRepository.findById(new WorkflowVersionPinId(callerId, workflowId))
                .map(WorkflowVersionServiceImpl::pinView)
                .orElseGet(() -> new VersionPinView(null, null, null));
    }

    @Override
    @Transactional
    public VersionPinView pin(UUID callerId, UUID workflowId, int version, String reason) {
        Workflow workflow = visible(callerId, workflowId);
        if (isOwner(workflow, callerId)) {
            LocalizedMessage refusal = new LocalizedMessage("workflow.version.ownerPin");
            throw new UnprocessableEntityException(refusal, null, Map.of("version", refusal));
        }
        versionRepository.findByWorkflowIdAndVersionAndReleasedAtIsNotNull(workflowId, version)
                .orElseThrow(() -> refused("version"));
        Instant pinnedAt = Instant.now();
        String note = emptyToNull(reason);
        pinRepository.upsert(callerId, workflowId, version, note, pinnedAt);
        return new VersionPinView(version, note, pinnedAt);
    }

    @Override
    @Transactional
    public void unpin(UUID callerId, UUID workflowId) {
        visible(callerId, workflowId);
        pinRepository.deleteById(new WorkflowVersionPinId(callerId, workflowId));
    }

    private Workflow visible(UUID callerId, UUID workflowId) {
        return workflowRepository.findVisibleTo(workflowId, callerId)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("workflow.notFound", workflowId)));
    }

    private static boolean isOwner(Workflow workflow, UUID callerId) {
        return workflow.getOwnerId().equals(callerId);
    }

    private List<WorkflowStep> liveSteps(Workflow workflow) {
        return workflowStepRepository.findByWorkflowIdOrderByStepOrderAsc(workflow.getId());
    }

    /** A non-owner reaches released versions only; an unreleased number answers like a missing one. */
    private Optional<WorkflowVersion> readable(Workflow workflow, boolean owner, int version) {
        return owner
                ? versionRepository.findByWorkflowIdAndVersion(workflow.getId(), version)
                : versionRepository.findByWorkflowIdAndVersionAndReleasedAtIsNotNull(workflow.getId(), version);
    }

    private static UnprocessableEntityException refused(String field) {
        LocalizedMessage reason = new LocalizedMessage("workflow.version.notInWorkflow");
        return new UnprocessableEntityException(reason, null, Map.of(field, reason));
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static Side parseSide(String field, String text, String fallback) {
        String value = text == null ? fallback : text;
        if (value.equals(DRAFT)) {
            return new Side(DRAFT, null);
        }
        if (value.equals(PUBLISHED)) {
            return new Side(PUBLISHED, null);
        }
        if (VERSION_NUMBER.matcher(value).matches()) {
            return new Side(value, Integer.parseInt(value));
        }
        throw new InvalidVersionSideException(field, new LocalizedMessage("workflow.version.sideInvalid"));
    }

    private Resolved resolve(Workflow workflow, boolean owner, Side side) {
        if (side.draft()) {
            return new Resolved(new DiffSide(DRAFT, null),
                    WorkflowSnapshots.of(workflow, liveSteps(workflow)));
        }
        WorkflowVersion version = side.number() != null
                ? readable(workflow, owner, side.number()).orElseThrow(() -> new NotFoundException(
                        new LocalizedMessage("workflow.version.notFound", side.number())))
                : published(workflow, owner);
        return new Resolved(new DiffSide(side.ref(), version.getVersion()),
                WorkflowSnapshots.parse(version.getSnapshot()));
    }

    /** The pointer's version; an owner whose workflow has no pointer yet gets the newest version instead. */
    private WorkflowVersion published(Workflow workflow, boolean owner) {
        Optional<WorkflowVersion> pointed = workflow.getPublishedVersionId() == null
                ? Optional.empty()
                : versionRepository.findById(workflow.getPublishedVersionId());
        if (pointed.isEmpty() && owner) {
            pointed = versionRepository.findTopByWorkflowIdOrderByVersionDesc(workflow.getId());
        }
        return pointed.orElseThrow(() -> new NotFoundException(new LocalizedMessage("workflow.version.noneYet")));
    }

    private static WorkflowVersionView view(WorkflowVersion version, Workflow workflow) {
        return new WorkflowVersionView(version.getId(), version.getVersion(), version.getChangelog(),
                version.getSource().name().toLowerCase(Locale.ROOT), version.getRestoredFromVersion(),
                version.getPublishedBy(), version.getPublishedAt(), version.getReleasedAt(),
                version.getId().equals(workflow.getPublishedVersionId()));
    }

    private static VersionSnapshotView snapshotView(WorkflowVersion version, boolean full) {
        JsonNode snapshot = WorkflowSnapshots.parse(version.getSnapshot());
        if (!full) {
            snapshot = WorkflowSnapshots.skeleton(snapshot);
        }
        List<VersionSnapshotView.Step> steps = new ArrayList<>();
        for (JsonNode step : snapshot.path("steps")) {
            JsonNode config = step.get("config");
            steps.add(new VersionSnapshotView.Step(UUID.fromString(step.path("id").asText()),
                    step.path("stepOrder").asInt(), text(step, "name"), step.path("type").asText(),
                    config == null || config.isNull() ? null : config, text(step, "inputRef")));
        }
        return new VersionSnapshotView(snapshot.path("name").asText(), !full, steps);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static VersionDiffView.StepChange stepChange(VersionDiff.StepChange change) {
        return new VersionDiffView.StepChange(change.stepId(), change.status().name().toLowerCase(Locale.ROOT),
                change.fromOrder(), change.toOrder(), change.name(), change.type(), change.changedFields(),
                change.before(), change.after());
    }

    private static VersionPinView pinView(WorkflowVersionPin pin) {
        return new VersionPinView(pin.getPinnedVersion(), pin.getReason(), pin.getPinnedAt());
    }

    /** A requested side: {@code number} is set for an explicit version, both null for draft or published. */
    private record Side(String ref, Integer number) {

        boolean draft() {
            return DRAFT.equals(ref);
        }
    }

    private record Resolved(DiffSide side, JsonNode snapshot) {
    }
}
