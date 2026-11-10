package com.flowcortex.llm.web;

import com.flowcortex.shared.web.LocalizedMessage;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * A model-registry refusal rendered as RFC 9457 problem {@code https://flow-cortex.dev/problems/<code>},
 * with optional field {@code errors}. The detail and every field error are message bundle keys, put into
 * the request's language by {@link LlmProblemHandler}; clients still map the code to their own copy.
 */
public class LlmProblemException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final LocalizedMessage detail;
    private final Map<String, LocalizedMessage> fieldErrors = new LinkedHashMap<>();

    public LlmProblemException(HttpStatus status, String code, LocalizedMessage detail) {
        super(detail.toString());
        this.status = status;
        this.code = code;
        this.detail = detail;
    }

    public LlmProblemException withFieldErrors(Map<String, LocalizedMessage> errors) {
        fieldErrors.putAll(errors);
        return this;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public LocalizedMessage detail() {
        return detail;
    }

    public Map<String, LocalizedMessage> fieldErrors() {
        return Collections.unmodifiableMap(fieldErrors);
    }
}
