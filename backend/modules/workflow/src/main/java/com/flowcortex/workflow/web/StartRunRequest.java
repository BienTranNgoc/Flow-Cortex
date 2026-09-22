package com.flowcortex.workflow.web;

import jakarta.validation.constraints.Size;

/**
 * The run's own input (design.md D5/R-042 — step 1 resolves to this).
 * Optional: not every workflow needs one (the seeded {@code echo} demo just
 * returns whatever is given, including nothing). Bounded like every other
 * request field in the change — the {@code input} column is unbounded
 * {@code text}, so without this an oversized body is an unchecked storage/DoS
 * vector.
 */
record StartRunRequest(@Size(max = 10_000) String input) {
}
