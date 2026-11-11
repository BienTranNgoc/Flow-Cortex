package com.flowcortex.workflow.web;

import com.flowcortex.workflow.api.AccessLogEntryView;
import com.flowcortex.workflow.api.SharePage;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One newest-first page of reads through the share key; {@code total7d} counts the last seven days. */
record AccessLogPageResponse(List<Entry> data, Meta meta) {

    record Entry(UUID id, Instant accessedAt, @Schema(nullable = true) UUID userId,
            @Schema(nullable = true) String name, @Schema(nullable = true) String network,
            @Schema(nullable = true) String userAgent) {
    }

    record Meta(boolean hasMore, @Schema(nullable = true) String nextCursor,
            @Schema(requiredMode = RequiredMode.REQUIRED) long total7d) {
    }

    static AccessLogPageResponse from(SharePage<AccessLogEntryView> page) {
        return new AccessLogPageResponse(
                page.data().stream().map(e -> new Entry(e.id(), e.accessedAt(), e.userId(), e.name(), e.network(),
                        e.userAgent())).toList(),
                new Meta(page.hasMore(), page.nextCursor(), page.total7d() == null ? 0L : page.total7d()));
    }
}
