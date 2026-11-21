package com.flowcortex.workflow.web;

import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.shared.security.authz.RequirePermission;
import com.flowcortex.shared.web.PageView;
import com.flowcortex.workflow.api.AdminRunFilter;
import com.flowcortex.workflow.api.AdminRunView;
import com.flowcortex.workflow.api.WorkflowRunService;
import com.flowcortex.workflow.application.DailySeries;
import com.flowcortex.workflow.application.RunCancellation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.LocaleResolver;

/**
 * {@code /api/v1/admin} — every user's runs and every remote step for staff holding the key each
 * route names. Not owner-scoped, which is why it sits behind the admin chain as well as the key
 * check. An out-of-range {@code page}/{@code size}, an unknown {@code status}, a malformed filter or a
 * {@code from} not before {@code to} is a 400 naming the parameter. Nothing here returns a run's or a
 * step's input or output.
 */
@RestController
@RequestMapping("/api/v1/admin")
class AdminRunsController {

    private static final int MAX_PAGE_SIZE = 100;
    /** Keeps {@code page * size} within an {@code int}, the offset type Spring Data pages with. */
    private static final int MAX_PAGE = Integer.MAX_VALUE / MAX_PAGE_SIZE;
    private static final int EXPORT_CAP = 1000;
    private static final URI VALIDATION_FAILED = URI.create("https://flow-cortex.dev/problems/validation-failed");
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final List<String> EXPORT_HEADER = List.of("Run ID", "Workflow ID", "Workflow", "Owner email",
            "Status", "Started at", "Finished at", "Duration (s)", "Credits charged", "Error");

    private final WorkflowRunService workflowRunService;
    private final RunCancellation runCancellation;
    private final Clock clock;
    private final MessageSource messages;
    private final LocaleResolver localeResolver;

    AdminRunsController(WorkflowRunService workflowRunService, RunCancellation runCancellation, Clock clock,
            MessageSource messages, LocaleResolver localeResolver) {
        this.workflowRunService = workflowRunService;
        this.runCancellation = runCancellation;
        this.clock = clock;
        this.messages = messages;
        this.localeResolver = localeResolver;
    }

    @Operation(operationId = "listAdminRuns")
    @RequirePermission(Permission.WORKFLOW_RUN_VIEW)
    @GetMapping(value = "/runs", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PageView<AdminRunResponse>> listRuns(
            @RequestParam(defaultValue = "0") @Min(0) @Max(MAX_PAGE) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size,
            @Parameter(schema = @Schema(allowableValues = {"running", "waiting_approval", "completed", "error",
                    "cancelling", "cancelled"}))
            @RequestParam(required = false)
            @Pattern(regexp = "running|waiting_approval|completed|error|cancelling|cancelled") String status,
            @RequestParam(required = false) UUID workflowId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        AdminRunFilter filter = filter(status, workflowId, from, to);
        return ResponseEntity.ok(workflowRunService.listAllRuns(filter, page, size).map(AdminRunResponse::from));
    }

    @Operation(operationId = "getAdminRun")
    @RequirePermission(Permission.WORKFLOW_RUN_VIEW)
    @GetMapping(value = "/runs/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AdminRunDetailResponse> getRun(@PathVariable UUID id) {
        return ResponseEntity.ok(AdminRunDetailResponse.from(workflowRunService.getAdminRun(id)));
    }

    /**
     * The newest {@value #EXPORT_CAP} runs matching the list filters as CSV; {@code X-Total-Count}
     * is how many match, so a caller can tell the file was cut.
     */
    @Operation(operationId = "exportAdminRuns")
    @RequirePermission(Permission.WORKFLOW_RUN_EXPORT)
    @GetMapping(value = "/runs/export", produces = "text/csv")
    ResponseEntity<String> exportRuns(
            @Parameter(schema = @Schema(allowableValues = {"running", "waiting_approval", "completed", "error",
                    "cancelling", "cancelled"}))
            @RequestParam(required = false)
            @Pattern(regexp = "running|waiting_approval|completed|error|cancelling|cancelled") String status,
            @RequestParam(required = false) UUID workflowId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        PageView<AdminRunView> page = workflowRunService.listAllRuns(filter(status, workflowId, from, to), 0,
                EXPORT_CAP);
        List<List<Object>> rows = page.items().stream().map(AdminRunsController::exportRow).toList();
        String fileName = "runs-" + FILE_DATE.format(clock.instant().atZone(DailySeries.ZONE)) + ".csv";
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(fileName).build().toString())
                .header("X-Total-Count", Long.toString(page.total()))
                .body(CsvTable.render(EXPORT_HEADER, rows));
    }

    /** Stops any member's run; the answer is the staff detail, never the member's own run view. */
    @Operation(operationId = "cancelAdminRun")
    @RequirePermission(Permission.WORKFLOW_RUN_CANCEL)
    @PostMapping(value = "/runs/{id}/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AdminRunDetailResponse> cancelRun(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        runCancellation.cancelRunAsAdmin(UUID.fromString(jwt.getSubject()), id);
        return ResponseEntity.ok(AdminRunDetailResponse.from(workflowRunService.getAdminRun(id)));
    }

    @Operation(operationId = "listAdminMediaJobs")
    @RequirePermission(Permission.WORKFLOW_RUN_VIEW)
    @GetMapping(value = "/media/jobs", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PageView<MediaJobResponse>> listMediaJobs(
            @RequestParam(defaultValue = "0") @Min(0) @Max(MAX_PAGE) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size,
            @Parameter(schema = @Schema(allowableValues = {"pending", "running", "completed", "error"}))
            @RequestParam(required = false) @Pattern(regexp = "pending|running|completed|error") String status) {
        return ResponseEntity.ok(workflowRunService.listRemoteSteps(page, size, status).map(MediaJobResponse::from));
    }

    @ExceptionHandler(RangeInvalidException.class)
    ProblemDetail rangeInvalid(HttpServletRequest request) {
        Locale locale = localeResolver.resolveLocale(request);
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                messages.getMessage("problem.validationFailed", null, locale));
        detail.setType(VALIDATION_FAILED);
        detail.setProperty("errors", Map.of("from", messages.getMessage("admin.runs.rangeInvalid", null, locale)));
        return detail;
    }

    private static AdminRunFilter filter(String status, UUID workflowId, Instant from, Instant to) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw new RangeInvalidException();
        }
        return new AdminRunFilter(status, workflowId, from, to);
    }

    private static List<Object> exportRow(AdminRunView run) {
        Long seconds = run.finishedAt() == null ? null
                : Duration.between(run.startedAt(), run.finishedAt()).toSeconds();
        return Arrays.asList(run.id().toString(), run.workflowId().toString(), run.workflowName(),
                run.ownerEmail(), run.status(), run.startedAt(), run.finishedAt(), seconds, run.creditsCharged(),
                run.errorMessage());
    }

    private static final class RangeInvalidException extends RuntimeException {
        RangeInvalidException() {
            super("from is not before to", null, false, false);
        }
    }
}
