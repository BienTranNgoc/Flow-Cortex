package com.flowcortex.workflow.domain;

import java.util.Locale;
import java.util.Optional;

/** Why a member reported a review; persisted and exposed as the lowercase name. */
public enum ReviewReportReason {
    SPAM,
    SCAM,
    HARMFUL,
    HARASSMENT,
    MISINFORMATION,
    COPYRIGHT,
    NSFW,
    OTHER;

    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The reason with this exact lowercase code, or empty. */
    public static Optional<ReviewReportReason> fromCode(String code) {
        for (ReviewReportReason reason : values()) {
            if (reason.code().equals(code)) {
                return Optional.of(reason);
            }
        }
        return Optional.empty();
    }
}
