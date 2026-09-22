package com.flowcortex.workflow.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Public view of a {@code run_step} row (design.md D5). {@code status} is
 * the exact lowercase wire value ({@code pending}|{@code running}|
 * {@code completed}|{@code error}, design.md D1) the acceptance oracles
 * assert on directly.
 */
public record RunStepView(
        UUID id, int stepOrder, String type, String status, String input, String output, String errorMessage,
        Instant startedAt, Instant finishedAt) {
}
