package com.flowcortex.entity.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

/** Restore body: the version the member is looking at, which must still be the entity's current one. */
public record RestoreEntityRequest(@Schema(requiredMode = RequiredMode.REQUIRED) Integer baseVersion) {
}
