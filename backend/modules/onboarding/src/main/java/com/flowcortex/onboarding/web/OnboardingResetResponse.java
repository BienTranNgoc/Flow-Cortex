package com.flowcortex.onboarding.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

/** Always {@code completed: false}: a reset clears the completion. */
@Schema(name = "OnboardingResetResponse")
record OnboardingResetResponse(@Schema(requiredMode = RequiredMode.REQUIRED) boolean completed) {
}
