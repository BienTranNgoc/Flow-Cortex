package com.flowcortex.workflow.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One item of an iterate step in the attempt the step is in now. {@code status} is the lowercase wire
 * value ({@code pending}|{@code running}|{@code completed}|{@code error}|{@code skipped}); {@code attempt}
 * is the item's own and grows when the owner regenerates it.
 */
public record RunStepChildView(
        UUID id, int itemIndex, String status, String output, String errorCode, String errorMessage, int attempt,
        Instant startedAt, Instant finishedAt, List<RunStepOutputView> outputs) {
}
