package com.flowcortex.workflow.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * The run's own input — step 1 resolves to this. Optional (not every workflow needs one).
 * Bounded like other request fields: {@code input} is unbounded {@code text} in the DB, so
 * without this limit an oversized body is an unchecked storage/DoS vector.
 *
 * <p>A workflow whose first step processes media takes {@code uploadId} (from {@code POST
 * /api/v1/uploads}) instead, plus the file's {@code originalName}, kept for display only; the
 * server trims it to 255 characters, so a longer name is accepted.
 *
 * <p>{@code options} travel with the run to its steps; omitted, the run gets the defaults.
 */
record StartRunRequest(@Size(max = 10_000) String input, UUID uploadId, @Size(max = 1024) String originalName,
        @Valid RunOptionsRequest options) {
}
