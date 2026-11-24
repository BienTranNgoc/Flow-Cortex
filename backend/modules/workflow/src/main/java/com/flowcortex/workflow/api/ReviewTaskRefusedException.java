package com.flowcortex.workflow.api;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import java.net.URI;
import org.springframework.http.HttpStatus;

/**
 * A 409 on a review decision that a client tells apart by {@code code} ({@code REVIEW_EXPIRED},
 * {@code REVIEW_QUESTIONS_NOT_READY}); every other refused decision keeps the code-less conflict.
 */
public class ReviewTaskRefusedException extends LocalizedStatusException {

    private static final URI TYPE = URI.create("https://flow-cortex.dev/problems/conflict");

    private final String code;
    private final LocalizedMessage detail;

    private ReviewTaskRefusedException(String code, LocalizedMessage detail) {
        super(HttpStatus.CONFLICT, detail);
        this.code = code;
        this.detail = detail;
        setType(TYPE);
        getBody().setProperty("code", code);
    }

    /** The review's time limit passed, or the sweep already ended it. */
    public static ReviewTaskRefusedException expired() {
        return new ReviewTaskRefusedException("REVIEW_EXPIRED", new LocalizedMessage("reviewTask.expired"));
    }

    /** The QA review's questions are still being written. */
    public static ReviewTaskRefusedException questionsNotReady() {
        return new ReviewTaskRefusedException("REVIEW_QUESTIONS_NOT_READY",
                new LocalizedMessage("reviewTask.questionsNotReady"));
    }

    public String code() {
        return code;
    }

    /** The bundle key of the member-facing sentence. */
    public LocalizedMessage detail() {
        return detail;
    }
}
