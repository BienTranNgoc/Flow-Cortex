package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.workflow.api.EditableFieldView;
import com.flowcortex.workflow.api.RunStepOutputEdited;
import com.flowcortex.workflow.api.StepEditView;
import com.flowcortex.workflow.application.EditedOutput.Applied;
import com.flowcortex.workflow.application.StepEditRules.StepFacts;
import com.flowcortex.workflow.application.StepEditRules.UndoPlan;
import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.domain.RunStepOutputVersion;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.infrastructure.RunStepOutputVersionRepository;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A member correcting the text of a finished step's result. Edit, undo and discard each run in one transaction that
 * locks the step row first, so one step changes one operation at a time and its current edit never disagrees with its
 * history. The engine's own output is never written: the edit sits beside it and
 * {@link RunStep#effectiveOutput()} is what later readers take.
 */
@Service
public class RunStepEdits {

    private static final Logger LOG = LoggerFactory.getLogger(RunStepEdits.class);
    private static final TypeReference<LinkedHashMap<String, String>> VALUES = new TypeReference<>() {
    };

    /** One requested change: a concrete JSON Pointer ({@code ""} is the whole output) and the new text. */
    public record FieldEdit(String pointer, String text) {
    }

    private final WorkflowRunRepository runRepository;
    private final RunStepRepository stepRepository;
    private final RunStepOutputVersionRepository versionRepository;
    private final RunUpdates runUpdates;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    /** Its own plain mapper, like {@link InputResolver}: this module runs on Jackson 2. */
    private final ObjectMapper json = new ObjectMapper();

    RunStepEdits(WorkflowRunRepository runRepository, RunStepRepository stepRepository,
            RunStepOutputVersionRepository versionRepository, RunUpdates runUpdates, ApplicationEventPublisher events,
            Clock clock) {
        this.runRepository = runRepository;
        this.stepRepository = stepRepository;
        this.versionRepository = versionRepository;
        this.runUpdates = runUpdates;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Applies {@code fields} onto the step's original output; fields not sent keep their current value.
     *
     * @throws NotFoundException for a run that is not the caller's, or a step that is not a top-level step of it
     * @throws StepEditRefusedException {@code step_not_editable}, or {@code output_revision_conflict} when
     *         {@code expectedRevision} is not the step's current revision
     * @throws StepEditRequestException when a field or the revision is not acceptable
     */
    @Transactional
    public StepEditView edit(UUID caller, UUID runId, UUID stepId, List<FieldEdit> fields, Integer expectedRevision) {
        WorkflowRun run = ownedRun(caller, runId);
        RunStep step = lockedStep(runId, stepId);
        int expected = validRevision(expectedRevision);
        requireEditable(step);
        if (StepEditRules.checkRevision(expected, StepEditRules.currentRevision(step.getEditSeq())).isPresent()) {
            throw conflict(runId, stepId);
        }
        Map<String, String> original = EditedOutput.values(step.getType(), step.getOutput());
        List<EditedOutput.FieldEdit> edits = fields == null ? null
                : fields.stream().map(f -> new EditedOutput.FieldEdit(f.pointer(), f.text())).toList();
        Applied applied;
        try {
            applied = EditedOutput.apply(step.getType(), step.getOutput(), currentValues(step, original), edits);
        } catch (FieldRefusal e) {
            throw invalid(e);
        }
        return store(run, step, original, applied);
    }

    /**
     * Makes the previous stored version the current edit as a new version, or clears the edit when only one version
     * is stored.
     *
     * @throws StepEditRefusedException {@code step_not_editable}, or {@code nothing_to_undo} when no edit is current
     */
    @Transactional
    public StepEditView undo(UUID caller, UUID runId, UUID stepId) {
        WorkflowRun run = ownedRun(caller, runId);
        RunStep step = lockedStep(runId, stepId);
        requireEditable(step);
        List<RunStepOutputVersion> newest = versionRepository.findTop2ByRunStepIdOrderByVersionDesc(stepId);
        UndoPlan plan = StepEditRules.undo(step.getEditedOutput() != null,
                newest.stream().map(RunStepOutputVersion::getVersion).toList());
        return switch (plan) {
            case UndoPlan.Refused _ -> throw StepEditRefusedException.nothingToUndo();
            case UndoPlan.ClearEdit _ -> clear(run, step);
            case UndoPlan.RestoreVersion _ -> {
                Map<String, String> original = EditedOutput.values(step.getType(), step.getOutput());
                Map<String, String> earlier = readValues(newest.get(1));
                yield store(run, step, original, reapply(step, earlier));
            }
        };
    }

    /**
     * Clears the current edit and keeps the history. A step with no current edit is left as it is, with no revision
     * change and no event.
     */
    @Transactional
    public StepEditView discard(UUID caller, UUID runId, UUID stepId) {
        WorkflowRun run = ownedRun(caller, runId);
        RunStep step = lockedStep(runId, stepId);
        if (step.getEditedOutput() == null) {
            return view(step, null, step.getOutput(), null, null, step.getEditSeq());
        }
        return clear(run, step);
    }

    private StepEditView clear(WorkflowRun run, RunStep step) {
        return write(run, step, null, null, null, step.getOutput());
    }

    /** Adds {@code applied} as the newest version and makes it the current edit. */
    private StepEditView store(WorkflowRun run, RunStep step, Map<String, String> original, Applied applied) {
        BigDecimal percent = EditDistance.percent(EditedOutput.diffText(original),
                EditedOutput.diffText(applied.values()));
        addVersion(step, applied.values());
        return write(run, step, applied.output(), clock.instant(), percent, applied.output());
    }

    private StepEditView write(WorkflowRun run, RunStep step, String editedOutput, Instant editedAt,
            BigDecimal percent, String fieldsFrom) {
        int updated = stepRepository.applyEdit(step.getId(), step.getEditSeq(), editedOutput, editedAt, percent);
        if (updated != 1) {
            throw conflict(run.getId(), step.getId());
        }
        runUpdates.touched(run.getId());
        events.publishEvent(new RunStepOutputEdited(run.getId(), run.getOwnerId()));
        return view(step, editedOutput, fieldsFrom, percent, editedAt, step.getEditSeq() + 1);
    }

    private StepEditView view(RunStep step, String editedOutput, String fieldsFrom, BigDecimal percent,
            Instant editedAt, int editSeq) {
        List<EditableFieldView> fields = EditedOutput.fields(step.getType(), fieldsFrom).stream()
                .map(f -> new EditableFieldView(f.pointer(), f.label(), f.index(), f.source(), f.text())).toList();
        return new StepEditView(step.getRunId(), step.getId(), editedOutput, fields, percent, editedAt,
                StepEditRules.currentRevision(editSeq));
    }

    private void addVersion(RunStep step, Map<String, String> values) {
        List<RunStepOutputVersion> newest = versionRepository.findTop2ByRunStepIdOrderByVersionDesc(step.getId());
        int next = newest.isEmpty() ? 1 : newest.getFirst().getVersion() + 1;
        try {
            versionRepository.saveAndFlush(new RunStepOutputVersion(UUID.randomUUID(), step.getId(), next,
                    writeValues(values), clock.instant()));
        } catch (DataIntegrityViolationException e) {
            throw conflict(step.getRunId(), step.getId());
        }
        versionRepository.deleteOldestBeyond(step.getId(), StepEditRules.MAX_VERSIONS);
    }

    /** The values the step shows now: the newest version while an edit is current, else the original's. */
    private Map<String, String> currentValues(RunStep step, Map<String, String> original) {
        if (step.getEditedOutput() == null) {
            return original;
        }
        List<RunStepOutputVersion> newest = versionRepository.findTop2ByRunStepIdOrderByVersionDesc(step.getId());
        if (newest.isEmpty()) {
            throw new IllegalStateException("Step " + step.getId() + " has an edit and no stored version");
        }
        return readValues(newest.getFirst());
    }

    /** Rebuilds the output of a stored version from the step's original; a stored version is not a request. */
    private Applied reapply(RunStep step, Map<String, String> values) {
        try {
            return EditedOutput.restore(step.getType(), step.getOutput(), values);
        } catch (FieldRefusal e) {
            throw new IllegalStateException("A stored version of step " + step.getId() + " no longer applies: "
                    + e.getMessage(), e);
        }
    }

    private void requireEditable(RunStep step) {
        StepFacts facts = new StepFacts(true, step.getType(), step.getStatus(), step.getOutput() != null,
                !EditedOutput.fields(step.getType(), step.getOutput()).isEmpty());
        if (StepEditRules.refusal(facts) != null) {
            throw StepEditRefusedException.notEditable();
        }
    }

    private WorkflowRun ownedRun(UUID caller, UUID runId) {
        return runRepository.findByIdAndOwnerId(runId, caller)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("workflow.run.notFound", runId)));
    }

    private RunStep lockedStep(UUID runId, UUID stepId) {
        return stepRepository.findByIdAndRunIdForUpdate(stepId, runId)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("workflow.runStep.notFound", stepId)));
    }

    private static int validRevision(Integer expectedRevision) {
        try {
            return StepEditRules.validateRevision(expectedRevision);
        } catch (FieldRefusal e) {
            throw invalid(e);
        }
    }

    private static StepEditRequestException invalid(FieldRefusal refusal) {
        LocalizedMessage detail = switch (refusal.reason()) {
            case TOO_LONG -> new LocalizedMessage("workflow.runStep.fieldTooLong");
            case BLANK -> new LocalizedMessage("workflow.runStep.fieldBlank");
            case NOT_EDITABLE -> new LocalizedMessage("workflow.runStep.fieldNotEditable");
            case INVALID, STRUCTURE -> new LocalizedMessage("workflow.runStep.fieldsInvalid");
        };
        return new StepEditRequestException(refusal.field(), detail);
    }

    private static StepEditRefusedException conflict(UUID runId, UUID stepId) {
        LOG.info("Step edit refused as a revision conflict: run {} step {}", runId, stepId);
        return StepEditRefusedException.revisionConflict();
    }

    private String writeValues(Map<String, String> values) {
        try {
            return json.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Edit values cannot be written", e);
        }
    }

    private Map<String, String> readValues(RunStepOutputVersion version) {
        try {
            return json.readValue(version.getContent(), VALUES);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Version " + version.getId() + " holds no field values", e);
        }
    }
}
