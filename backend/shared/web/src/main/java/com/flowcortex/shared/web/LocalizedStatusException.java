package com.flowcortex.shared.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * A {@link ResponseStatusException} whose problem {@code detail} is a bundle key, resolved in the
 * request's language by {@link GlobalExceptionHandler}. The reason is the key, so logs stay greppable.
 */
public class LocalizedStatusException extends ResponseStatusException {

    private final LocalizedMessage detail;

    public LocalizedStatusException(HttpStatus status, LocalizedMessage detail) {
        super(status, detail.toString());
        this.detail = detail;
    }

    @Override
    public String getDetailMessageCode() {
        return detail.code();
    }

    @Override
    public Object[] getDetailMessageArguments() {
        return detail.args();
    }
}
