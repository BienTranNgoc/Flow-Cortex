package com.flowcortex.user.api;

/** Why a Google sign-in did not produce a session; {@link #code()} is the {@code oauth_error} the login page receives. */
public enum OAuthFailure {

    CANCELLED("cancelled"),
    INVALID_STATE("invalid_state"),
    REGISTRATION_CLOSED("registration_closed"),
    LINK_REQUIRED("link_required"),
    ACCOUNT_INACTIVE("account_inactive"),
    RATE_LIMITED("rate_limited"),
    FAILED("failed");

    private final String code;

    OAuthFailure(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
