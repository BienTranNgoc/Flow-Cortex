package com.flowcortex.user.activity;

import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.shared.security.authz.RequirePermission;
import com.flowcortex.user.api.ActivityCursor;
import com.flowcortex.user.api.UserActivityItem;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.LocaleResolver;

/**
 * {@code GET /api/v1/users/{id}/activity} — one member's activity, newest first, paged by an opaque cursor. A
 * malformed cursor is a validation failure naming {@code cursor}, answered like any other bad parameter.
 */
@RestController
@RequestMapping("/api/v1/users")
class UserActivityController {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;
    private static final URI VALIDATION_FAILED = URI.create("https://flow-cortex.dev/problems/validation-failed");

    private final UserActivityService service;
    private final MessageSource messages;
    private final LocaleResolver localeResolver;

    UserActivityController(UserActivityService service, MessageSource messages, LocaleResolver localeResolver) {
        this.service = service;
        this.messages = messages;
        this.localeResolver = localeResolver;
    }

    record ActivityItemResponse(
            UUID id,
            @Schema(allowableValues = {"admin_action", "auth", "workflow", "workflow_run", "support_request"})
            String source,
            String action,
            @Schema(allowableValues = {"admin", "user"}) String actorType,
            String actorLabel,
            String objectType,
            String objectId,
            String objectLabel,
            String status,
            Instant occurredAt) {

        static ActivityItemResponse from(UserActivityItem item) {
            return new ActivityItemResponse(item.id(), item.source(), item.action(), item.actorType(),
                    item.actorLabel(), item.objectType(), item.objectId(), item.objectLabel(), item.status(),
                    item.occurredAt());
        }
    }

    record ActivityPageResponse(List<ActivityItemResponse> items, String nextCursor) {
    }

    private static final class InvalidCursorException extends RuntimeException {

        InvalidCursorException(Throwable cause) {
            super("The activity cursor is malformed", cause);
        }
    }

    @Operation(operationId = "getUserActivity")
    @RequirePermission(Permission.USER_ACCOUNT_VIEW)
    @GetMapping(value = "/{id}/activity", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ActivityPageResponse> activity(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) @Min(1) @Max(MAX_LIMIT) int limit,
            @RequestParam(required = false) String cursor) {
        ActivityCursor after = decode(cursor);
        UserActivityService.ActivityPage page = service.page(UUID.fromString(jwt.getSubject()), id, after, limit);
        return ResponseEntity.ok(new ActivityPageResponse(
                page.items().stream().map(ActivityItemResponse::from).toList(), page.nextCursor()));
    }

    private static ActivityCursor decode(String cursor) {
        if (cursor == null) {
            return null;
        }
        try {
            return ActivityCursor.decode(cursor);
        } catch (IllegalArgumentException ex) {
            throw new InvalidCursorException(ex);
        }
    }

    @ExceptionHandler(InvalidCursorException.class)
    ProblemDetail invalidCursor(InvalidCursorException ex, HttpServletRequest request) {
        var locale = localeResolver.resolveLocale(request);
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                messages.getMessage("problem.validationFailed", null, locale));
        detail.setType(VALIDATION_FAILED);
        detail.setProperty("errors", Map.of("cursor", messages.getMessage("validation.wrongType", null, locale)));
        return detail;
    }
}
