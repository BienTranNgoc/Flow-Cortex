package com.flowcortex.onboarding.web;

import com.flowcortex.onboarding.application.OnboardingSampleService.AdminView;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The onboarding sample as the admin console reads it. {@code shown} is true only while the journey
 * switch is on and the workflow is eligible; {@code reason} then says why not (an eligibility code,
 * or {@code onboarding_off}). {@code translations} has one entry per editable language.
 */
@Schema(name = "AdminOnboardingSample")
record AdminOnboardingSample(
        @Schema(requiredMode = RequiredMode.REQUIRED, types = {"object", "null"}) Workflow workflow,
        @Schema(requiredMode = RequiredMode.REQUIRED) boolean shown,
        @Schema(requiredMode = RequiredMode.REQUIRED, types = {"string", "null"}) String reason,
        @Schema(requiredMode = RequiredMode.REQUIRED, types = {"string", "null"}) String sampleInput,
        @Schema(requiredMode = RequiredMode.REQUIRED, types = {"string", "null"}, allowableValues = {"vi", "en"})
        String sourceLocale,
        @Schema(requiredMode = RequiredMode.REQUIRED, types = {"string", "null"}) String sourceHash,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "one entry per editable language",
                additionalPropertiesSchema = TranslationText.class)
        Map<String, String> translations,
        @Schema(requiredMode = RequiredMode.REQUIRED, types = {"string", "null"}) String updatedBy,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant updatedAt) {

    /** Schema-only marker: a language's translation, null while none is stored. */
    @Schema(name = "AdminOnboardingSampleTranslation", types = {"string", "null"})
    static final class TranslationText {
        private TranslationText() {
        }
    }

    @Schema(name = "AdminOnboardingSampleWorkflow")
    record Workflow(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = RequiredMode.REQUIRED) String name) {
    }

    static AdminOnboardingSample of(AdminView view) {
        Workflow workflow = view.workflow() == null ? null : new Workflow(view.workflow().id(), view.workflow().name());
        return new AdminOnboardingSample(workflow, view.shown(), view.reason(), view.sampleInput(),
                view.sourceLocale(), view.sourceHash(), view.translations(), view.updatedBy(), view.updatedAt());
    }
}
