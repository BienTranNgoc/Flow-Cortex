package com.flowcortex.shared.web;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exists only so {@link GlobalExceptionHandlerTest} has real endpoints to
 * exercise the advice against — never shipped, test sources only.
 */
@RestController
class ThrowawayTestController {

    static final Instant LOCKED_UNTIL = Instant.parse("2026-01-01T10:15:30Z");

    @GetMapping("/test/not-found")
    String triggerNotFound() {
        throw new NotFoundException(new LocalizedMessage(GlobalExceptionHandlerTest.THING_NOT_FOUND));
    }

    @GetMapping("/test/conflict")
    String triggerConflict() {
        throw new ConflictException(new LocalizedMessage(GlobalExceptionHandlerTest.THING_CONFLICTS));
    }

    @GetMapping("/test/unprocessable")
    String triggerUnprocessable() {
        throw new UnprocessableEntityException(new LocalizedMessage(GlobalExceptionHandlerTest.THING_INCOMPLETE),
                "THING_INCOMPLETE", Map.of("steps[1].config", new LocalizedMessage(GlobalExceptionHandlerTest.THING_MISSING)));
    }

    @GetMapping("/test/optimistic-lock")
    String triggerOptimisticLock() {
        throw new ObjectOptimisticLockingFailureException(Object.class, "thing-id");
    }

    @PostMapping("/test/validate")
    String triggerValidation(@Valid @RequestBody ThrowawayRequest request) {
        return "ok";
    }

    @GetMapping("/test/page")
    String triggerParameterValidation(@RequestParam(defaultValue = "20") @Max(100) int size) {
        return "ok";
    }

    @GetMapping("/test/unauthenticated")
    String triggerUnauthenticated() {
        throw new BadCredentialsException("bad password");
    }

    @GetMapping("/test/forbidden")
    String triggerForbidden() {
        throw new AccessDeniedException("not an admin");
    }

    @GetMapping("/test/locked")
    String triggerLocked() {
        throw new AccountLockedException(new LocalizedMessage("auth.accountLocked", LOCKED_UNTIL), LOCKED_UNTIL);
    }

    @GetMapping("/test/constraint-violation")
    String triggerConstraintViolation() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            throw new ConstraintViolationException(factory.getValidator().validate(new ThrowawayRequest("")));
        }
    }

    @GetMapping("/test/localized-not-found")
    String triggerLocalizedNotFound() {
        throw new NotFoundException(new LocalizedMessage("workflow.notFound", "x"));
    }

    @GetMapping("/test/rate-limited")
    String triggerRateLimited() {
        throw new RetryLaterException();
    }

    @GetMapping("/test/unexpected")
    String triggerUnexpected() {
        throw new IllegalStateException("db password is hunter2");
    }

    @PostMapping("/test/constraints")
    String triggerConstraints(@Valid @RequestBody ConstraintsRequest request) {
        return "ok";
    }

    record ThrowawayRequest(@NotBlank String name) {
    }

    record ConstraintsRequest(@NotNull String present, @NotEmpty List<String> items,
            @Size(min = 1, max = 10_000) String text, @Email String email, @Pattern(regexp = "9:16") String ratio,
            @AssertTrue Boolean accepted, @Max(100) Integer cap, @Min(1) Integer floor, @Positive Integer count) {
    }

    static class RetryLaterException extends LocalizedStatusException {

        RetryLaterException() {
            super(HttpStatus.TOO_MANY_REQUESTS, new LocalizedMessage("auth.rateLimited.login"));
        }

        @Override
        public HttpHeaders getHeaders() {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.RETRY_AFTER, "30");
            return headers;
        }
    }
}
