package com.flowcortex.workflow.web;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.PageView;
import com.flowcortex.workflow.api.RunHistoryService;
import com.flowcortex.workflow.api.runhistory.RunExport;
import com.flowcortex.workflow.api.runhistory.RunHistoryFilter;
import com.flowcortex.workflow.api.runhistory.RunHistoryItem;
import com.flowcortex.workflow.api.runhistory.RunHistoryPatch;
import com.flowcortex.workflow.api.runhistory.RunHistoryRequestException;
import com.flowcortex.workflow.application.DailySeries;
import com.flowcortex.workflow.web.RunHistoryResponses.RunHistoryRowResponse;
import com.flowcortex.workflow.web.RunHistoryResponses.RunTrayResponse;
import com.flowcortex.workflow.web.RunHistoryResponses.UsageSummaryResponse;
import com.flowcortex.workflow.web.RunHistoryResponses.WorkflowOptionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/runs} — the caller's own run history: list, filter choices, rename and star, delete, CSV
 * export, usage summary and the top-bar tray. Always owner-scoped; a deleted run is never shown.
 */
@RestController
@RequestMapping("/api/v1/runs")
class RunHistoryController {

    private static final int MAX_PAGE_SIZE = 100;
    /** Keeps {@code page * size} within an {@code int}, the offset type Spring Data pages with. */
    private static final int MAX_PAGE = Integer.MAX_VALUE / MAX_PAGE_SIZE;
    private static final Set<String> STATUSES = Set.of("running", "waiting_approval", "completed", "error",
            "cancelling", "cancelled");
    private static final List<String> EXPORT_HEADER = List.of("Run ID", "Workflow", "Title", "Status", "Favorite",
            "Started at", "Finished at", "Duration (s)", "Credits charged", "Error");
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final RunHistoryService history;
    private final CurrentUserResolver currentUserResolver;
    private final Clock clock;

    RunHistoryController(RunHistoryService history, CurrentUserResolver currentUserResolver, Clock clock) {
        this.history = history;
        this.currentUserResolver = currentUserResolver;
        this.clock = clock;
    }

    @Operation(operationId = "listRuns")
    @GetMapping(value = "", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PageView<RunHistoryRowResponse>> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") @Min(0) @Max(MAX_PAGE) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size,
            @Parameter(schema = @Schema(allowableValues = {"running", "waiting_approval", "completed", "error",
                    "cancelling", "cancelled"})) @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID workflowId,
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "false") boolean favorite) {
        UUID currentUserId = currentUserResolver.require(jwt);
        RunHistoryFilter filter = filter(status, workflowId, from, to, favorite);
        return ResponseEntity.ok(history.list(currentUserId, filter, page, size).map(RunHistoryRowResponse::from));
    }

    @Operation(operationId = "listRunWorkflows")
    @GetMapping(value = "/workflows", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<List<WorkflowOptionResponse>> workflows(@AuthenticationPrincipal Jwt jwt) {
        UUID currentUserId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(history.workflows(currentUserId).stream().map(WorkflowOptionResponse::from).toList());
    }

    /** At most 1000 runs, newest first; {@code X-Total-Count} is the number of runs matching the filters. */
    @Operation(operationId = "exportRuns", responses = @ApiResponse(responseCode = "200",
            content = @Content(mediaType = "text/csv", schema = @Schema(type = "string", format = "binary")),
            headers = @Header(name = "X-Total-Count", schema = @Schema(type = "integer", format = "int64"))))
    @GetMapping("/export")
    ResponseEntity<byte[]> export(@AuthenticationPrincipal Jwt jwt,
            @Parameter(schema = @Schema(allowableValues = {"running", "waiting_approval", "completed", "error",
                    "cancelling", "cancelled"})) @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID workflowId,
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "false") boolean favorite) {
        UUID currentUserId = currentUserResolver.require(jwt);
        RunExport export = history.export(currentUserId, filter(status, workflowId, from, to, favorite));
        List<List<Object>> rows = new ArrayList<>(export.items().size());
        for (RunHistoryItem item : export.items()) {
            rows.add(row(item));
        }
        String fileName = "runs-" + FILE_DATE.format(DailySeries.dayOf(clock.instant())) + ".csv";
        return ResponseEntity.ok()
                .contentType(CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fileName).build().toString())
                .header("X-Total-Count", Long.toString(export.total()))
                .body(CsvTable.render(EXPORT_HEADER, rows).getBytes(StandardCharsets.UTF_8));
    }

    @Operation(operationId = "getRunUsageSummary")
    @GetMapping(value = "/usage-summary", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<UsageSummaryResponse> usageSummary(@AuthenticationPrincipal Jwt jwt,
            @Parameter(schema = @Schema(type = "integer", defaultValue = "30", minimum = "1", maximum = "365"))
            @RequestParam(defaultValue = "30") String days,
            @Parameter(schema = @Schema(type = "integer", defaultValue = "10", minimum = "1", maximum = "50"))
            @RequestParam(defaultValue = "10") String top) {
        UUID currentUserId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(UsageSummaryResponse.from(
                history.usageSummary(currentUserId, wholeNumber("days", days, new LocalizedMessage("workflow.run.days.invalid")),
                        wholeNumber("top", top, new LocalizedMessage("workflow.run.top.invalid")))));
    }

    @Operation(operationId = "getRunTray")
    @GetMapping(value = "/tray", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<RunTrayResponse> tray(@AuthenticationPrincipal Jwt jwt) {
        UUID currentUserId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(RunTrayResponse.from(history.tray(currentUserId)));
    }

    @Operation(operationId = "updateRun")
    @PatchMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<RunHistoryRowResponse> update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestBody UpdateRunRequest request) {
        UUID currentUserId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(RunHistoryRowResponse.from(history.update(currentUserId, id, patch(request))));
    }

    @Operation(operationId = "deleteRun")
    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        history.delete(currentUserResolver.require(jwt), id);
        return ResponseEntity.noContent().build();
    }

    private static RunHistoryFilter filter(String status, UUID workflowId, Instant from, Instant to,
            boolean favorite) {
        if (status != null && !STATUSES.contains(status)) {
            throw new RunHistoryRequestException("status", new LocalizedMessage("workflow.run.status.invalid"));
        }
        if (from != null && to != null && !from.isBefore(to)) {
            throw new RunHistoryRequestException("from", new LocalizedMessage("workflow.run.range.invalid"));
        }
        return new RunHistoryFilter(status, workflowId, from, to, favorite);
    }

    private static int wholeNumber(String name, String value, LocalizedMessage invalid) {
        String trimmed = value.trim();
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            // A number beyond int range is still a whole number: the service clamps it.
            if (trimmed.matches("[+-]?\\d+")) {
                return trimmed.startsWith("-") ? Integer.MIN_VALUE : Integer.MAX_VALUE;
            }
            throw new RunHistoryRequestException(name, invalid);
        }
    }

    private static RunHistoryPatch patch(UpdateRunRequest request) {
        RunHistoryPatch patch = new RunHistoryPatch(request.titleSent(), request.title(), request.favorite());
        if (patch.isEmpty()) {
            throw new RunHistoryRequestException("body", new LocalizedMessage("workflow.run.patch.empty"));
        }
        return patch;
    }

    private static List<Object> row(RunHistoryItem item) {
        List<Object> cells = new ArrayList<>(EXPORT_HEADER.size());
        cells.add(item.id().toString());
        cells.add(item.workflowName());
        cells.add(item.title());
        cells.add(item.status());
        cells.add(item.favorite());
        cells.add(item.startedAt());
        cells.add(item.finishedAt());
        cells.add(item.durationSeconds());
        cells.add(item.creditsCharged());
        cells.add(item.errorMessage());
        return cells;
    }
}
