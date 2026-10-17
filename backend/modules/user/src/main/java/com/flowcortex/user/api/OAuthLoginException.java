package com.flowcortex.user.api;

/** A Google sign-in that was refused; the refusal is already audited when this is thrown. */
public class OAuthLoginException extends RuntimeException {

    private final OAuthFailure failure;

    public OAuthLoginException(OAuthFailure failure) {
        super("Google sign-in refused: " + failure.code());
        this.failure = failure;
    }

    public OAuthFailure failure() {
        return failure;
    }
}
