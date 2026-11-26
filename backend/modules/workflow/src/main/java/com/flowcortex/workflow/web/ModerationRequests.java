package com.flowcortex.workflow.web;

import io.swagger.v3.oas.annotations.media.Schema;

/** Wire shapes of the staff moderation writes. */
final class ModerationRequests {

    private ModerationRequests() {
    }

    /**
     * No bean constraint on purpose: presence, trimming and length are {@code ModerationRules}, so a refusal is a
     * 422 naming the field and a value that is under the limit once trimmed is accepted. {@code reason} is required
     * for hide, remove and restore and optional for dismiss and resolve.
     */
    record ModerationRequest(
            @Schema(maxLength = 1000, nullable = true) String reason,
            @Schema(maxLength = 1000, nullable = true) String internalNote) {

        static final ModerationRequest EMPTY = new ModerationRequest(null, null);

        static ModerationRequest orEmpty(ModerationRequest request) {
            return request == null ? EMPTY : request;
        }
    }
}
