package com.flowcortex.user.application;

/**
 * Thrown when a Google id_token fails any of the four mandatory checks
 * (signature, {@code iss}, {@code aud}, {@code exp}, {@code email_verified}).
 * The OAuth callback maps this to {@code 400 Bad Request}.
 */
public class OAuthVerificationException extends RuntimeException {

    public OAuthVerificationException(String message) {
        super(message);
    }
}
