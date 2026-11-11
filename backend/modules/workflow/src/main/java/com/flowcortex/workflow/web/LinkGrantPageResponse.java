package com.flowcortex.workflow.web;

import com.flowcortex.workflow.api.LinkGrantView;
import com.flowcortex.workflow.api.SharePage;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One newest-first page of the members admitted through the share key. */
record LinkGrantPageResponse(List<Grant> data, Meta meta) {

    record Grant(UUID userId, @Schema(nullable = true) String name, Instant grantedAt) {
    }

    record Meta(boolean hasMore, @Schema(nullable = true) String nextCursor) {
    }

    static LinkGrantPageResponse from(SharePage<LinkGrantView> page) {
        return new LinkGrantPageResponse(
                page.data().stream().map(g -> new Grant(g.userId(), g.name(), g.grantedAt())).toList(),
                new Meta(page.hasMore(), page.nextCursor()));
    }
}
