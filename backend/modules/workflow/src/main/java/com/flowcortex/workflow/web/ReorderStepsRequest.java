package com.flowcortex.workflow.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** The workflow's step ids in the order they should run: every current step, each exactly once. */
record ReorderStepsRequest(@NotNull @Size(max = 32767) List<@NotNull UUID> stepIds) {
}
