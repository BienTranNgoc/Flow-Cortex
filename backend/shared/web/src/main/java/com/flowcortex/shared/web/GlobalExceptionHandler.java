package com.flowcortex.shared.web;

import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.lang.annotation.Annotation;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSource;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Global exception -&gt; {@link ProblemDetail} mapping, uniform across every module.
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} so standard Spring MVC failures keep their
 * correct status and {@code ProblemDetail} shape; this class adds the application exceptions
 * plus the {@code errors} field map for validation failures.
 *
 * <p>{@link AuthenticationException} and {@link AccessDeniedException} are handled here too:
 * :shared:security's filter chain hands both to Spring MVC's exception-resolver chain (which
 * this advice participates in), so 401/403 render through the same contract.
 *
 * <p>Every {@code detail} and {@code errors} value is written in the language the request's
 * {@code Accept-Language} resolves to. The locale is resolved from the request itself rather than
 * {@code LocaleContextHolder}, which is not yet set when a failure comes from the security chain.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final URI PROBLEM_BASE = URI.create("https://flow-cortex.dev/problems/");
    private static final Set<String> NON_ARGUMENT_ATTRIBUTES = Set.of("message", "groups", "payload");

    private final MessageSource messages;
    private final LocaleResolver localeResolver;

    public GlobalExceptionHandler(MessageSource messages, LocaleResolver localeResolver) {
        this.messages = messages;
        this.localeResolver = localeResolver;
    }

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail handleNotFound(NotFoundException ex, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "not-found", detail(ex.getDetail(), request));
    }

    @ExceptionHandler(ConflictException.class)
    public ProblemDetail handleConflict(ConflictException ex, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "conflict", detail(ex.getDetail(), request));
    }

    /**
     * Two requests changed the same versioned row at once and this one lost; Spring's {@link
     * OptimisticLockingFailureException} covers a lost {@code @Version} check at commit.
     */
    @ExceptionHandler({OptimisticLockingFailureException.class, OptimisticLockException.class})
    public ProblemDetail handleOptimisticLock(Exception ex, HttpServletRequest request) {
        LOG.info("Concurrent update lost on {} {}: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        return problem(HttpStatus.CONFLICT, "conflict",
                message("problem.conflict.concurrentUpdate", locale(request)));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail handleUnauthenticated(AuthenticationException ex, HttpServletRequest request) {
        return problem(HttpStatus.UNAUTHORIZED, "unauthenticated", message("problem.unauthenticated", locale(request)));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return problem(HttpStatus.FORBIDDEN, "forbidden", message("problem.forbidden", locale(request)));
    }

    @ExceptionHandler(AccountLockedException.class)
    public ProblemDetail handleAccountLocked(AccountLockedException ex, HttpServletRequest request) {
        ProblemDetail detail = problem(HttpStatus.LOCKED, "account-locked",
                detail(ex.getDetail(), request));
        detail.setProperty("lockedUntil", ex.getLockedUntil());
        return detail;
    }

    /**
     * Takes no request parameter because it is also called where no request may be bound to the
     * thread; the locale comes from the bound request, or is English when there is none.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex) {
        Locale locale = currentRequestLocale();
        ProblemDetail detail = problem(HttpStatus.BAD_REQUEST, "validation-failed",
                message("problem.validationFailed", locale));
        Map<String, String> errors = new LinkedHashMap<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            errors.put(violation.getPropertyPath().toString(), violationMessage(violation, locale));
        }
        detail.setProperty("errors", errors);
        return detail;
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Locale locale = locale(request);
        ProblemDetail detail = createProblemDetail(ex, status, message("problem.validationFailed", locale), null, null,
                request);
        detail.setType(PROBLEM_BASE.resolve("validation-failed"));
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            errors.put(fieldError.getField(), messages.getMessage(fieldError, locale));
        }
        detail.setProperty("errors", errors);
        return ResponseEntity.status(status).headers(headers).body(detail);
    }

    /** A constrained {@code @RequestParam}/{@code @PathVariable}: {@code errors} is keyed by the parameter name. */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Locale locale = locale(request);
        ProblemDetail detail = createProblemDetail(ex, status, message("problem.validationFailed", locale), null, null,
                request);
        detail.setType(PROBLEM_BASE.resolve("validation-failed"));
        Map<String, String> errors = new LinkedHashMap<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            errors.put(result.getMethodParameter().getParameterName(),
                    messages.getMessage(result.getResolvableErrors().getFirst(), locale));
        }
        detail.setProperty("errors", errors);
        return ResponseEntity.status(status).headers(headers).body(detail);
    }

    /** A request parameter that does not convert to its type ({@code page=abc}): {@code errors} names it. */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        Locale locale = locale(request);
        String name = ex instanceof MethodArgumentTypeMismatchException mismatch
                ? mismatch.getName() : ex.getPropertyName();
        ProblemDetail detail = createProblemDetail(ex, status, message("problem.validationFailed", locale), null, null,
                request);
        detail.setType(PROBLEM_BASE.resolve("validation-failed"));
        detail.setProperty("errors",
                Map.of(name == null ? "request" : name, message("validation.wrongType", locale)));
        return ResponseEntity.status(status).headers(headers).body(detail);
    }

    /** Resolves a {@link LocalizedStatusException}'s detail key in the request's language, not the thread's. */
    @Override
    protected ResponseEntity<Object> handleErrorResponseException(ErrorResponseException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        ProblemDetail body = ex.updateAndGetBody(messages, locale(request));
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    /**
     * Catches everything the handlers above don't recognise. Never leaks the raw exception
     * message to the client — logs it instead, diagnosable via the request's correlation id
     * ({@code RequestIdFilter}'s MDC entry).
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
        LOG.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        ProblemDetail detail = problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error",
                message("problem.unexpected", locale(request)));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(detail);
    }

    private Locale locale(HttpServletRequest request) {
        return localeResolver.resolveLocale(request);
    }

    private Locale locale(WebRequest request) {
        return request instanceof ServletWebRequest servletRequest
                ? locale(servletRequest.getRequest()) : LocaleConfig.DEFAULT_LOCALE;
    }

    private Locale currentRequestLocale() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? locale(attributes.getRequest()) : LocaleConfig.DEFAULT_LOCALE;
    }

    private String message(String code, Locale locale) {
        return messages.getMessage(code, null, locale);
    }

    private String detail(LocalizedMessage detail, HttpServletRequest request) {
        return messages.getMessage(detail, locale(request));
    }

    /**
     * A violation carrying its constraint's default wording is resolved through the bundle key named
     * after the constraint; a custom wording has no key and is written as is.
     */
    private String violationMessage(ConstraintViolation<?> violation, Locale locale) {
        Annotation constraint = violation.getConstraintDescriptor().getAnnotation();
        if (!violation.getMessageTemplate().equals(defaultTemplate(constraint))) {
            return violation.getMessage();
        }
        List<Object> arguments = new ArrayList<>();
        arguments.add(violation.getPropertyPath().toString());
        new TreeMap<>(violation.getConstraintDescriptor().getAttributes()).forEach((name, value) -> {
            if (!NON_ARGUMENT_ATTRIBUTES.contains(name)) {
                arguments.add(value);
            }
        });
        String resolved = messages.getMessage(constraint.annotationType().getSimpleName(), arguments.toArray(), null,
                locale);
        return resolved == null ? violation.getMessage() : resolved;
    }

    private static Object defaultTemplate(Annotation constraint) {
        try {
            return constraint.annotationType().getMethod("message").getDefaultValue();
        } catch (NoSuchMethodException ex) {
            return null;
        }
    }

    private static ProblemDetail problem(HttpStatus status, String typeSlug, String detail) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
        problemDetail.setType(PROBLEM_BASE.resolve(typeSlug));
        return problemDetail;
    }
}
