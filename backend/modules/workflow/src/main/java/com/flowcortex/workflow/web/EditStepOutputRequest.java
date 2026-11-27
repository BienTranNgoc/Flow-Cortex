package com.flowcortex.workflow.web;

import com.flowcortex.workflow.application.RunStepEdits.FieldEdit;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * The texts to change in a step's result and the revision of the step the client loaded. {@code expectedRevision}
 * is read by {@link StrictIntegerDeserializer}, so only a JSON whole number reaches validation; which pointers and
 * texts are acceptable for the step's type is decided by the service.
 */
record EditStepOutputRequest(
        @NotNull @Size(min = 1, max = 2000) List<@Valid FieldEditRequest> fields,
        @JsonDeserialize(using = StrictIntegerDeserializer.class) @NotNull @Min(1)
        @Schema(implementation = Integer.class, minimum = "1") Integer expectedRevision) {

    /** One change: {@code pointer} is a concrete JSON Pointer ({@code ""} is the whole output). */
    record FieldEditRequest(@NotNull String pointer, @NotNull String text) {
    }

    List<FieldEdit> toEdits() {
        return fields.stream().map(field -> new FieldEdit(field.pointer(), field.text())).toList();
    }
}
