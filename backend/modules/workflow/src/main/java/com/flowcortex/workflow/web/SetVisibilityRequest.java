package com.flowcortex.workflow.web;

import com.flowcortex.workflow.api.WorkflowVisibility;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.Locale;

/**
 * The target tier and, for {@code unlisted} only, when the share key stops working. {@code visibility} and
 * {@code expiresAt} are bound as text so a bad value is an error that names the field (400 and 422), not a body
 * binding failure with no field; the service parses {@code expiresAt}.
 */
record SetVisibilityRequest(
        @NotNull @Pattern(regexp = "private|unlisted|public")
        @Schema(allowableValues = {"private", "unlisted", "public"}) String visibility,
        @Schema(nullable = true, type = "string", format = "date-time") String expiresAt) {

    WorkflowVisibility target() {
        return WorkflowVisibility.valueOf(visibility.toUpperCase(Locale.ROOT));
    }
}
