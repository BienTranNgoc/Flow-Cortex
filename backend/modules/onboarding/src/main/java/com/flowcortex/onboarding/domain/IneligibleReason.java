package com.flowcortex.onboarding.domain;

/** Why a workflow cannot be the onboarding sample; {@link #code()} is the wire and message-key suffix. */
public enum IneligibleReason {
    NOT_FOUND("not_found"),
    DELETED("deleted"),
    UNPUBLISHED("unpublished"),
    PRIVATE("private"),
    NO_STEPS("no_steps"),
    UNAVAILABLE("unavailable"),
    NEEDS_UPLOAD("needs_upload"),
    NEEDS_LINK("needs_link"),
    MANUAL_STEP("manual_step");

    private final String code;

    IneligibleReason(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
