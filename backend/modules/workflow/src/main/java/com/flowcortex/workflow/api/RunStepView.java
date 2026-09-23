package com.flowcortex.workflow.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Public view of a {@code run_step} row. {@code status} is the exact
 * lowercase wire value ({@code pending}|{@code running}|{@code completed}|
 * {@code error}) the acceptance oracles assert on directly.
 */
public record RunStepView(
        UUID id, int stepOrder, String type, String status, String input, String output, String errorMessage,
        Instant startedAt, Instant finishedAt) {
}
