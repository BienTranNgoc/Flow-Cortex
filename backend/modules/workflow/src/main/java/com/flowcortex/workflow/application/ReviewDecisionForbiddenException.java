package com.flowcortex.workflow.application;

import com.flowcortex.shared.web.LocalizedMessage;
import org.springframework.security.access.AccessDeniedException;

/** A review decision the member may not take, with the reason the review endpoints write as the 403 detail. */
public class ReviewDecisionForbiddenException extends AccessDeniedException {

    private final LocalizedMessage detail;

    public ReviewDecisionForbiddenException(LocalizedMessage detail) {
        super(detail.toString());
        this.detail = detail;
    }

    public LocalizedMessage getDetail() {
        return detail;
    }
}
