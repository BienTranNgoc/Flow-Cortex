package com.flowcortex.media.api;

import jakarta.validation.constraints.NotEmpty;

/** A reference to an object already in the store; the worker fetches nothing by URL. */
public record MediaJobInput(@NotEmpty String key, @NotEmpty String bucket, @NotEmpty String objectKey) {
}
