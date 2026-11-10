package com.flowcortex.app.clienterrors;

import org.springframework.http.HttpStatus;

/** The classes of refused report; each carries the problem code, the status and the bundle key of its detail. */
enum ClientErrorRefusal {

    CONTENT_TYPE("client_error_content_type", HttpStatus.BAD_REQUEST, "clientErrors.contentType"),
    UNREADABLE("client_error_unreadable", HttpStatus.BAD_REQUEST, "clientErrors.unreadable"),
    INVALID("client_error_invalid", HttpStatus.BAD_REQUEST, "clientErrors.invalid"),
    TOO_LARGE("client_error_too_large", HttpStatus.CONTENT_TOO_LARGE, "clientErrors.tooLarge"),
    RATE_LIMITED("client_error_rate_limited", HttpStatus.TOO_MANY_REQUESTS, "clientErrors.rateLimited");

    private final String code;
    private final HttpStatus status;
    private final String messageKey;

    ClientErrorRefusal(String code, HttpStatus status, String messageKey) {
        this.code = code;
        this.status = status;
        this.messageKey = messageKey;
    }

    String code() {
        return code;
    }

    HttpStatus status() {
        return status;
    }

    String messageKey() {
        return messageKey;
    }
}
