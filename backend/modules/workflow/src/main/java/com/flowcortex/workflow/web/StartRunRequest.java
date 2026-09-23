package com.flowcortex.workflow.web;

import jakarta.validation.constraints.Size;

/**
 * The run's own input — step 1 resolves to this. Optional (not every workflow needs one).
 * Bounded like other request fields: {@code input} is unbounded {@code text} in the DB, so
 * without this limit an oversized body is an unchecked storage/DoS vector.
 */
record StartRunRequest(@Size(max = 10_000) String input) {
}
