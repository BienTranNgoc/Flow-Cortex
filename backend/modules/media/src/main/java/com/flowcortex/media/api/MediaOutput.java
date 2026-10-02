package com.flowcortex.media.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

/** One object the worker wrote; {@code meta} is optional. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MediaOutput(
        @NotEmpty String name,
        @NotEmpty String bucket,
        @NotEmpty String objectKey,
        @NotEmpty String mime,
        @NotNull @Min(0) Long bytes,
        Map<String, Object> meta) {
}
