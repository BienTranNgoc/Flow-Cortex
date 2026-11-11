package com.flowcortex.workflow.web;

import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.shared.security.authz.RequirePermission;
import com.flowcortex.shared.web.PageView;
import com.flowcortex.workflow.api.AdminWorkflowFilter;
import com.flowcortex.workflow.api.AdminWorkflowRow;
import com.flowcortex.workflow.application.DailySeries;
import com.flowcortex.workflow.application.WorkflowUsage;
import com.flowcortex.workflow.web.AdminWorkflowResponses.AdminWorkflowResponse;
import com.flowcortex.workflow.web.AdminWorkflowResponses.WorkflowReportResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/admin/workflows} reads — every owner's live workflows with their run totals, a CSV of
 * them, and one workflow's usage report. The list and the report need {@code workflow.workflow.view}, the
 * export {@code workflow.workflow.export}. The writes live in {@link AdminWorkflowsController}. An
 * out-of-range {@code page}/{@code size}, an unknown {@code status} or an over-long {@code q} is a 400
 * naming the parameter.
 */
@RestController
@RequestMapping("/api/v1/admin/workflows")
class AdminWorkflowReportsController {

    static final String TOTAL_COUNT_HEADER = "X-Total-Count";
    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final int MAX_PAGE_SIZE = 100;
    /** Keeps {@code page * size} within an {@code int}, the offset type the query pages with. */
    private static final int MAX_PAGE = Integer.MAX_VALUE / MAX_PAGE_SIZE;
    private static final int MAX_QUERY_LENGTH = 255;
    private static final List<String> EXPORT_HEADER = List.of("ID", "Name", "Owner email", "Status", "Shared",
            "Steps", "Runs", "Runs 7d", "Last run at", "Created at", "Updated at");

    private final WorkflowUsage workflowUsage;
    private final Clock clock;

    AdminWorkflowReportsController(WorkflowUsage workflowUsage, Clock clock) {
        this.workflowUsage = workflowUsage;
        this.clock = clock;
    }

    @Operation(operationId = "listAdminWorkflows")
    @RequirePermission(Permission.WORKFLOW_WORKFLOW_VIEW)
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PageView<AdminWorkflowResponse>> listWorkflows(
            @RequestParam(defaultValue = "0") @Min(0) @Max(MAX_PAGE) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size,
            @Parameter(schema = @Schema(allowableValues = {"draft", "published"}))
            @RequestParam(required = false) @Pattern(regexp = "draft|published") String status,
            @RequestParam(required = false) @Size(max = MAX_QUERY_LENGTH) String q) {
        return ResponseEntity.ok(workflowUsage.listWorkflows(filter(status, q), page, size)
                .map(AdminWorkflowResponse::from));
    }

    @Operation(operationId = "exportAdminWorkflows")
    @RequirePermission(Permission.WORKFLOW_WORKFLOW_EXPORT)
    @GetMapping(value = "/export", produces = "text/csv")
    ResponseEntity<String> exportWorkflows(
            @Parameter(schema = @Schema(allowableValues = {"draft", "published"}))
            @RequestParam(required = false) @Pattern(regexp = "draft|published") String status,
            @RequestParam(required = false) @Size(max = MAX_QUERY_LENGTH) String q) {
        PageView<AdminWorkflowRow> page = workflowUsage.exportWorkflows(filter(status, q));
        String fileName = "workflows-" + FILE_DATE.format(DailySeries.dayOf(clock.instant())) + ".csv";
        return ResponseEntity.ok()
                .contentType(CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .header(TOTAL_COUNT_HEADER, Long.toString(page.total()))
                .body(CsvTable.render(EXPORT_HEADER, page.items().stream().map(AdminWorkflowReportsController::exportRow)
                        .toList()));
    }

    @Operation(operationId = "getAdminWorkflowReport")
    @RequirePermission(Permission.WORKFLOW_WORKFLOW_VIEW)
    @GetMapping(value = "/{id}/report", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<WorkflowReportResponse> getReport(@PathVariable UUID id) {
        return ResponseEntity.ok(WorkflowReportResponse.from(workflowUsage.report(id)));
    }

    private static AdminWorkflowFilter filter(String status, String q) {
        String trimmed = q == null ? null : q.strip();
        return new AdminWorkflowFilter(status, trimmed == null || trimmed.isEmpty() ? null : trimmed);
    }

    private static List<Object> exportRow(AdminWorkflowRow row) {
        return Arrays.asList(row.id(), row.name(), row.ownerEmail(), row.status(), row.shared(),
                row.steps(), row.runs(), row.runsLast7Days(), row.lastRunAt(), row.createdAt(), row.updatedAt());
    }
}
