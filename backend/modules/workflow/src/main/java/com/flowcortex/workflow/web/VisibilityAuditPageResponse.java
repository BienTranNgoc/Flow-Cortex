package com.flowcortex.workflow.web;

import com.flowcortex.workflow.api.SharePage;
import com.flowcortex.workflow.api.VisibilityAuditEntryView;
import com.flowcortex.workflow.api.WorkflowVisibility;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One newest-first page of visibility changes and key rotations. */
record VisibilityAuditPageResponse(List<Entry> data, Meta meta) {

    record Entry(UUID id, Instant changedAt, UUID changedBy, @Schema(nullable = true) String name,
            WorkflowVisibility fromVisibility, WorkflowVisibility toVisibility,
            @Schema(allowableValues = {"owner", "admin", "key_regenerated"}) String triggeredBy,
            @Schema(nullable = true) String network, @Schema(nullable = true) String userAgent) {
    }

    record Meta(boolean hasMore, @Schema(nullable = true) String nextCursor) {
    }

    static VisibilityAuditPageResponse from(SharePage<VisibilityAuditEntryView> page) {
        return new VisibilityAuditPageResponse(
                page.data().stream().map(e -> new Entry(e.id(), e.changedAt(), e.changedBy(), e.name(),
                        e.fromVisibility(), e.toVisibility(), e.triggeredBy(), e.network(), e.userAgent())).toList(),
                new Meta(page.hasMore(), page.nextCursor()));
    }
}
