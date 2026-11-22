package com.flowcortex.workflow.web;

import jakarta.validation.constraints.NotBlank;

/** The token a review answered; {@code acknowledgeOutboundTargets} must be true when that review is outbound. */
record ImportWorkflowRequest(@NotBlank String token, Boolean acknowledgeOutboundTargets) {
}
