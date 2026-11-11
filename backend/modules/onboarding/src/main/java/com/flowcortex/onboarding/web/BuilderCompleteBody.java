package com.flowcortex.onboarding.web;

import io.swagger.v3.oas.annotations.media.Schema;

/** Documents the builder completion body for the published contract only; the reason is optional. */
@Schema(name = "BuilderCompleteBody")
record BuilderCompleteBody(@Schema(allowableValues = {"skipped", "finished"}) String reason) {
}
