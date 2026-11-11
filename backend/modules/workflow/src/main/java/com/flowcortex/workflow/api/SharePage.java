package com.flowcortex.workflow.api;

import java.util.List;

/**
 * One newest-first page. {@code nextCursor} is {@code null} on the last page; {@code total7d} is set only on the
 * access log and counts the entries of the last seven days.
 */
public record SharePage<T>(List<T> data, boolean hasMore, String nextCursor, Long total7d) {
}
