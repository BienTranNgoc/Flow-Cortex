package com.flowcortex.user.application;

/**
 * Raised when a refresh token is submitted that has already been used or
 * revoked. The handler responds with HTTP 401 and the family is revoked.
 */
public class ReuseDetectedException extends RuntimeException {

    public ReuseDetectedException(String message) {
        super(message);
    }
}
