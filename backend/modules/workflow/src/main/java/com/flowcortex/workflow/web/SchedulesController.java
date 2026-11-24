package com.flowcortex.workflow.web;

import com.flowcortex.credit.api.CreditAccounts;
import com.flowcortex.shared.web.PageView;
import com.flowcortex.workflow.api.schedule.ScheduleService;
import com.flowcortex.workflow.web.ScheduleRequests.SetScheduleRequest;
import com.flowcortex.workflow.web.ScheduleResponses.ScheduleFireResponse;
import com.flowcortex.workflow.web.ScheduleResponses.ScheduleResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/workflows/{id}/schedule} — the caller's own schedule for a workflow: set, read, pause, resume,
 * delete, run now, and the history of its fires. Every route acts only on the caller's rows; the service answers
 * 503 {@code feature.turnedOff} while the schedules switch is off. Authenticated by {@code :shared:security}'s
 * default chain.
 */
@RestController
@RequestMapping("/api/v1/workflows/{id}/schedule")
class SchedulesController {

    private static final int MAX_PAGE_SIZE = 100;
    /** Keeps {@code page * size} within an {@code int}, the offset type Spring Data pages with. */
    private static final int MAX_PAGE = Integer.MAX_VALUE / MAX_PAGE_SIZE;

    private final ScheduleService schedules;
    private final CurrentUserResolver currentUserResolver;
    private final CreditAccounts creditAccounts;

    SchedulesController(ScheduleService schedules, CurrentUserResolver currentUserResolver,
            CreditAccounts creditAccounts) {
        this.schedules = schedules;
        this.currentUserResolver = currentUserResolver;
        this.creditAccounts = creditAccounts;
    }

    @ApiResponse(responseCode = "400", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @Operation(operationId = "setWorkflowSchedule", summary = "Create or replace the caller's schedule")
    @ResponseStatus(HttpStatus.OK)
    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ScheduleResponse> set(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody SetScheduleRequest request) {
        UUID callerId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(ScheduleResponse.from(schedules.set(callerId, id, request.toSetSchedule())));
    }

    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @Operation(operationId = "getWorkflowSchedule", summary = "The caller's schedule for the workflow")
    @ResponseStatus(HttpStatus.OK)
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ScheduleResponse> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        UUID callerId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(ScheduleResponse.from(schedules.get(callerId, id)));
    }

    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @Operation(operationId = "deleteWorkflowSchedule", summary = "Delete the caller's schedule; its fires stay")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        schedules.delete(currentUserResolver.require(jwt), id);
        return ResponseEntity.noContent().build();
    }

    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @Operation(operationId = "pauseWorkflowSchedule", summary = "Pause the caller's schedule")
    @ResponseStatus(HttpStatus.OK)
    @PostMapping(value = "/pause", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ScheduleResponse> pause(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        UUID callerId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(ScheduleResponse.from(schedules.pause(callerId, id)));
    }

    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @Operation(operationId = "resumeWorkflowSchedule", summary = "Resume the caller's schedule")
    @ResponseStatus(HttpStatus.OK)
    @PostMapping(value = "/resume", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ScheduleResponse> resume(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        UUID callerId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(ScheduleResponse.from(schedules.resume(callerId, id)));
    }

    @ApiResponse(responseCode = "402", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @Operation(operationId = "runWorkflowScheduleNow",
            summary = "Start one run with the schedule's input now, without changing the schedule")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping(value = "/run-now", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<RunResponse> runNow(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        UUID callerId = currentUserResolver.require(jwt);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(RunResponse.from(schedules.runNow(callerId, id), creditAccounts));
    }

    @ApiResponse(responseCode = "400", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @Operation(operationId = "listWorkflowScheduleRuns", summary = "The caller's fires of the workflow, newest first")
    @ResponseStatus(HttpStatus.OK)
    @GetMapping(value = "/runs", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PageView<ScheduleFireResponse>> history(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestParam(defaultValue = "0") @Min(0) @Max(MAX_PAGE) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        UUID callerId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(schedules.history(callerId, id, page, size).map(ScheduleFireResponse::from));
    }
}
