package com.flowcortex.workflow.web;

import com.flowcortex.media.api.UploadRejectedException;
import java.net.URI;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * A refused upload renders like a field validation failure, so the form shows the sentence on the
 * field it names. Ordered first: the shared handler's catch-all would otherwise turn it into a 500.
 */
@RestControllerAdvice(assignableTypes = {UploadsController.class, WorkflowsController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
class UploadRejectionHandler {

    private static final URI VALIDATION_FAILED = URI.create("https://flow-cortex.dev/problems/validation-failed");

    @ExceptionHandler(UploadRejectedException.class)
    ProblemDetail rejected(UploadRejectedException e) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
        detail.setType(VALIDATION_FAILED);
        detail.setProperty("errors", Map.of(e.field(), e.getMessage()));
        return detail;
    }
}
