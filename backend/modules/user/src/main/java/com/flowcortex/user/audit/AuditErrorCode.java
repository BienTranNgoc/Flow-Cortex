package com.flowcortex.user.audit;

import com.flowcortex.shared.web.LocalizedProblem;
import com.flowcortex.shared.web.LocalizedStatusException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * The stable code stored for a failed admin write. Only message keys, fixed slugs and class names
 * are read: an exception's message can carry row data the client never saw.
 */
final class AuditErrorCode {

    static final int MAX_LENGTH = 120;

    private AuditErrorCode() {}

    /** Returns null when there is neither an exception nor an error status. */
    static String of(Throwable error, int status) {
        String code = error == null ? fromStatus(status) : fromException(error);
        return code == null || code.length() <= MAX_LENGTH ? code : code.substring(0, MAX_LENGTH);
    }

    private static String fromException(Throwable error) {
        if (error instanceof LocalizedProblem problem && problem.localizedDetail() != null) {
            return problem.localizedDetail().code();
        }
        if (error instanceof LocalizedStatusException localized) {
            return localized.getDetailMessageCode();
        }
        if (error instanceof MethodArgumentNotValidException
                || error instanceof HandlerMethodValidationException
                || error instanceof HttpMessageNotReadableException
                || error instanceof MethodArgumentTypeMismatchException) {
            return "validation-failed";
        }
        if (error instanceof AccessDeniedException) {
            return "forbidden";
        }
        String simpleName = error.getClass().getSimpleName();
        return simpleName.isEmpty() ? error.getClass().getName() : simpleName;
    }

    private static String fromStatus(int status) {
        return status >= 400 ? "http-" + status : null;
    }
}
