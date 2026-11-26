package com.flowcortex.workflow.api.batch;

import java.util.List;

/**
 * What a member sends to start a batch: exactly one of {@code rows} and {@code content}. {@code format} is
 * {@code null} to detect it, {@code concurrency} is {@code null} for one, and {@code name} may be blank.
 */
public record CreateBatch(List<String> rows, String content, BatchFormat format, boolean skipFirstLine, String name,
        Integer concurrency, boolean acknowledgeCost) {
}
