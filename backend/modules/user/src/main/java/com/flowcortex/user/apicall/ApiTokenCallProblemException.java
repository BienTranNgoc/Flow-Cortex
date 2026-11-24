package com.flowcortex.user.apicall;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedProblem;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * A refusal of the call guards rendered as RFC 9457 problem {@code https://flow-cortex.dev/problems/<code>}.
 * The detail and each field error are bundle keys, put into the request's language by
 * {@link ApiTokenCallProblemHandler}.
 */
class ApiTokenCallProblemException extends RuntimeException implements LocalizedProblem {

    private final HttpStatus status;
    private final String code;
    private final LocalizedMessage detail;
    private final Map<String, LocalizedMessage> fieldErrors;

    private ApiTokenCallProblemException(HttpStatus status, String code, LocalizedMessage detail,
            Map<String, LocalizedMessage> fieldErrors) {
        super(detail.toString());
        this.status = status;
        this.code = code;
        this.detail = detail;
        this.fieldErrors = fieldErrors;
    }

    /** 400 {@code validation-failed} naming the {@code Idempotency-Key} field. */
    static ApiTokenCallProblemException keyInvalid() {
        return new ApiTokenCallProblemException(HttpStatus.BAD_REQUEST, "validation-failed",
                new LocalizedMessage("problem.validationFailed"),
                Map.of(IdempotencyKey.HEADER, new LocalizedMessage("apiToken.idempotency.keyInvalid",
                        IdempotencyKey.MAX_LENGTH)));
    }

    /** 400: the request cannot be fingerprinted, so a key is refused rather than ignored. */
    static ApiTokenCallProblemException unsupported(long maxBodyKibibytes) {
        return new ApiTokenCallProblemException(HttpStatus.BAD_REQUEST, "idempotency-unsupported",
                new LocalizedMessage("apiToken.idempotency.unsupported", maxBodyKibibytes), Map.of());
    }

    static ApiTokenCallProblemException inProgress() {
        return new ApiTokenCallProblemException(HttpStatus.CONFLICT, "idempotency-in-progress",
                new LocalizedMessage("apiToken.idempotency.inProgress"), Map.of());
    }

    static ApiTokenCallProblemException keyReused() {
        return new ApiTokenCallProblemException(HttpStatus.UNPROCESSABLE_CONTENT, "idempotency-key-reused",
                new LocalizedMessage("apiToken.idempotency.reused"), Map.of());
    }

    HttpStatus status() {
        return status;
    }

    String code() {
        return code;
    }

    Map<String, LocalizedMessage> fieldErrors() {
        return fieldErrors;
    }

    @Override
    public LocalizedMessage localizedDetail() {
        return detail;
    }
}
