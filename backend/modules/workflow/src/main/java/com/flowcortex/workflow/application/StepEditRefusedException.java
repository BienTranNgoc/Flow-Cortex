package com.flowcortex.workflow.application;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import java.net.URI;
import org.springframework.http.HttpStatus;

/**
 * A 409 on a step edit that a client tells apart by {@code code}: {@code step_not_editable},
 * {@code output_revision_conflict} or {@code nothing_to_undo}.
 */
public class StepEditRefusedException extends LocalizedStatusException {

    private static final URI TYPE = URI.create("https://flow-cortex.dev/problems/conflict");

    private final String code;

    private StepEditRefusedException(String code, LocalizedMessage detail) {
        super(HttpStatus.CONFLICT, detail);
        this.code = code;
        setType(TYPE);
        getBody().setProperty("code", code);
    }

    /** The step's result is not text the member may edit (not finished, a machine report, an approved checkpoint). */
    static StepEditRefusedException notEditable() {
        return new StepEditRefusedException(StepEditRules.NOT_EDITABLE,
                new LocalizedMessage("workflow.runStep.notEditable"));
    }

    /** Another tab changed the step since the client loaded it. */
    static StepEditRefusedException revisionConflict() {
        return new StepEditRefusedException(StepEditRules.REVISION_CONFLICT,
                new LocalizedMessage("workflow.runStep.revisionConflict"));
    }

    static StepEditRefusedException nothingToUndo() {
        return new StepEditRefusedException(StepEditRules.NOTHING_TO_UNDO,
                new LocalizedMessage("workflow.runStep.nothingToUndo"));
    }

    public String code() {
        return code;
    }
}
