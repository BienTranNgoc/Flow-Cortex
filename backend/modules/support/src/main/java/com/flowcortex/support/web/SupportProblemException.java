package com.flowcortex.support.web;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * A support refusal rendered as RFC 9457 problem {@code https://flow-cortex.dev/problems/<code>}, with
 * optional extra members (for example {@code articleCount}). The detail is server text for logs and
 * developers; clients map the code to their own copy.
 */
public class SupportProblemException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> properties = new LinkedHashMap<>();

    public SupportProblemException(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public SupportProblemException with(String name, Object value) {
        properties.put(name, value);
        return this;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> properties() {
        return Collections.unmodifiableMap(properties);
    }
}
