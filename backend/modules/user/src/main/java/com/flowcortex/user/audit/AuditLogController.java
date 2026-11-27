package com.flowcortex.user.audit;

import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.shared.security.authz.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.MessageSource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.LocaleResolver;
import tools.jackson.databind.JsonNode;

/**
 * {@code /api/v1/admin/audit-log} — the audit trail read side: a keyset-paged list, its facets and a
 * recorded CSV export. The export service finishes before any header is set, so a refusal is an
 * ordinary problem response.
 */
@RestController
@RequestMapping("/api/v1/admin/audit-log")
class AuditLogController {

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 100;
    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);
    private static final URI VALIDATION_FAILED = URI.create("https://flow-cortex.dev/problems/validation-failed");

    private final AuditLogRepository repository;
    private final AuditLogExportService exportService;
    private final AuditFacets facets;
    private final MessageSource messages;
    private final LocaleResolver localeResolver;
    private final Clock clock;

    AuditLogController(AuditLogRepository repository, AuditLogExportService exportService, AuditFacets facets,
            MessageSource messages, LocaleResolver localeResolver, Clock clock) {
        this.repository = repository;
        this.exportService = exportService;
        this.facets = facets;
        this.messages = messages;
        this.localeResolver = localeResolver;
        this.clock = clock;
    }

    @Operation(operationId = "listAuditLog")
    @RequirePermission(Permission.SYSTEM_AUDIT_LOG_VIEW)
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    AuditLogPage list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) UUID actorUserId,
            @Parameter(schema = @Schema(allowableValues = {"success", "failed"}))
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        AuditLogFilter filter = AuditLogFilter.of(q, action, resourceType, resourceId, actorUserId, status, from, to);
        AuditLogCursor after = cursor == null ? null : AuditLogCursor.decode(cursor);
        List<AuditLogRepository.Row> rows = repository.page(filter, after, size + 1);
        boolean more = rows.size() > size;
        List<AuditLogRepository.Row> page = more ? rows.subList(0, size) : rows;
        String next = more
                ? new AuditLogCursor(page.getLast().occurredAt(), page.getLast().id()).encode()
                : null;
        return new AuditLogPage(page.stream().map(AuditLogItem::of).toList(), next);
    }

    @Operation(operationId = "getAuditLogFacets")
    @RequirePermission(Permission.SYSTEM_AUDIT_LOG_VIEW)
    @GetMapping(value = "/facets", produces = MediaType.APPLICATION_JSON_VALUE)
    AuditLogFacets facets() {
        return new AuditLogFacets(facets.actions(), facets.resourceTypes());
    }

    @Operation(operationId = "exportAuditLog")
    @RequirePermission(Permission.SYSTEM_AUDIT_LOG_EXPORT)
    @GetMapping(value = "/export", produces = {"text/csv", MediaType.APPLICATION_PROBLEM_JSON_VALUE})
    ResponseEntity<byte[]> export(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) UUID actorUserId,
            @Parameter(schema = @Schema(allowableValues = {"success", "failed"}))
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        AuditLogFilter filter = AuditLogFilter.of(q, action, resourceType, resourceId, actorUserId, status, from, to);
        byte[] file = exportService.export(UUID.fromString(jwt.getSubject()), filter, request.getRemoteAddr(),
                request.getHeader(HttpHeaders.USER_AGENT));
        return ResponseEntity.ok()
                .contentType(CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(AuditLogCsv.fileName(clock.instant())).build().toString())
                .body(file);
    }

    @ExceptionHandler(AuditLogFilter.Invalid.class)
    ProblemDetail invalidParameters(AuditLogFilter.Invalid ex, HttpServletRequest request) {
        Locale locale = localeResolver.resolveLocale(request);
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                messages.getMessage("problem.validationFailed", null, locale));
        detail.setType(VALIDATION_FAILED);
        Map<String, String> errors = new LinkedHashMap<>();
        ex.errors().forEach((name, key) -> errors.put(name, messages.getMessage(key, null, locale)));
        detail.setProperty("errors", errors);
        return detail;
    }

    record AuditLogPage(List<AuditLogItem> items, @Schema(nullable = true) String nextCursor) {
    }

    record AuditLogFacets(List<String> actions, List<String> resourceTypes) {
    }

    /** A row as any admin sees it: the label of a member's row is masked, staff identities are shown. */
    record AuditLogItem(UUID id, Instant occurredAt, UUID actorUserId, @Schema(nullable = true) String actorEmail,
            @Schema(nullable = true) String actorName, String action, String resourceType,
            @Schema(nullable = true) String resourceId, @Schema(nullable = true) String resourceLabel,
            @Schema(type = "object", nullable = true) JsonNode changeSummary, String status,
            @Schema(nullable = true) String errorCode, @Schema(nullable = true) String ip,
            @Schema(nullable = true) String userAgent, String origin) {

        static AuditLogItem of(AuditLogRepository.Row row) {
            return new AuditLogItem(row.id(), row.occurredAt(), row.actorUserId(), row.actorEmail(),
                    row.actorName(), row.action(), row.resourceType(), row.resourceId(), row.label(),
                    row.changeSummary(), row.status(), row.errorCode(), row.ip(), row.userAgent(), row.origin());
        }
    }
}
