package com.flowcortex.workflow.web;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** The released version number to pin the caller's own runs to, with an optional reason. */
record PinVersionRequest(@NotNull @Min(1) Integer version, @Size(max = 200) String reason) {
}
