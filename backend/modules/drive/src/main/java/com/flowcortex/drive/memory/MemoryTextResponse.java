package com.flowcortex.drive.memory;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

/** The full text of a text entry. */
record MemoryTextResponse(@Schema(requiredMode = RequiredMode.REQUIRED) String text) {
}
