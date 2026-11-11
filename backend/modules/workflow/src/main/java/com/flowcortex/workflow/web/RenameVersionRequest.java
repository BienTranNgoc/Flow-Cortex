package com.flowcortex.workflow.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** The new name of a version; an empty string clears it. */
record RenameVersionRequest(@NotNull @Size(max = 200) String changelog) {
}
