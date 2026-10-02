package com.flowcortex.shared.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;
import java.util.function.Function;

/**
 * One page of a server-paginated list: {@code page} is zero-based, {@code size} the requested
 * page size and {@code total} the number of matching rows across all pages.
 */
public record PageView<T>(
        @Schema(requiredMode = RequiredMode.REQUIRED) List<T> items,
        @Schema(requiredMode = RequiredMode.REQUIRED) int page,
        @Schema(requiredMode = RequiredMode.REQUIRED) int size,
        @Schema(requiredMode = RequiredMode.REQUIRED) long total) {

    public <R> PageView<R> map(Function<? super T, ? extends R> mapper) {
        return new PageView<>(items.stream().<R>map(mapper).toList(), page, size, total);
    }
}
