package com.flowcortex.workflow.api;

import java.time.Instant;
import java.util.UUID;

/**
 * The admin run list's filters; a {@code null} field matches every run. {@code status} is a lowercase
 * run status; {@code from} is inclusive and {@code to} exclusive on the start time.
 */
public record AdminRunFilter(String status, UUID workflowId, Instant from, Instant to) {
}
