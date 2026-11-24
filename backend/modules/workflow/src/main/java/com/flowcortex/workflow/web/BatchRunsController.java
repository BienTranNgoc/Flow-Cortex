package com.flowcortex.workflow.web;

import com.flowcortex.shared.web.PageView;
import com.flowcortex.workflow.api.batch.BatchRunService;
import com.flowcortex.workflow.web.BatchRunRequests.CreateBatchRequest;
import com.flowcortex.workflow.web.BatchRunResponses.BatchResponse;
import com.flowcortex.workflow.web.BatchRunResponses.BatchRowResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * A member's batches of runs of one workflow: create from a list of rows or file text, follow, pause, resume,
 * cancel, read the rows and retry the failed ones. Always owner-scoped: another member's or an unknown batch is a
 * 404, never a 403.
 */
@RestController
@RequestMapping("/api/v1")
class BatchRunsController {

    private static final int MAX_BATCH_PAGE_SIZE = 100;
    private static final int MAX_ROW_PAGE_SIZE = 200;
    /** Keeps {@code page * size} within an {@code int}, the offset type Spring Data pages with. */
    private static final int MAX_BATCH_PAGE = Integer.MAX_VALUE / MAX_BATCH_PAGE_SIZE;
    private static final int MAX_ROW_PAGE = Integer.MAX_VALUE / MAX_ROW_PAGE_SIZE;

    private final BatchRunService batches;
    private final CurrentUserResolver currentUserResolver;

    BatchRunsController(BatchRunService batches, CurrentUserResolver currentUserResolver) {
        this.batches = batches;
        this.currentUserResolver = currentUserResolver;
    }

    /** Stores a running batch with one pending row per input; its rows start in order, a few at a time. */
    @ApiResponse(responseCode = "400", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "402", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @Operation(operationId = "createBatch")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping(value = "/workflows/{id}/batches", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<BatchResponse> create(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody CreateBatchRequest request) {
        UUID currentUserId = currentUserResolver.require(jwt);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(BatchResponse.from(batches.create(currentUserId, id, request.toCommand())));
    }

    /** The caller's batches, newest first. */
    @Operation(operationId = "listBatches")
    @GetMapping(value = "/batches", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PageView<BatchResponse>> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") @Min(0) @Max(MAX_BATCH_PAGE) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_BATCH_PAGE_SIZE) int size) {
        UUID currentUserId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(batches.list(currentUserId, page, size).map(BatchResponse::from));
    }

    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @Operation(operationId = "getBatch")
    @ResponseStatus(HttpStatus.OK)
    @GetMapping(value = "/batches/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<BatchResponse> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        UUID currentUserId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(BatchResponse.from(batches.get(currentUserId, id)));
    }

    /** The batch's rows in row order, optionally of one status; a row whose run is not started yet is pending. */
    @ApiResponse(responseCode = "400", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @Operation(operationId = "listBatchRows")
    @ResponseStatus(HttpStatus.OK)
    @GetMapping(value = "/batches/{id}/rows", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PageView<BatchRowResponse>> rows(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Parameter(schema = @Schema(allowableValues = {"pending", "running", "completed", "failed",
                    "cancelled"})) @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") @Min(0) @Max(MAX_ROW_PAGE) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(MAX_ROW_PAGE_SIZE) int size) {
        UUID currentUserId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(batches.rows(currentUserId, id, status, page, size).map(BatchRowResponse::from));
    }

    /** Stops new rows from starting; rows already running go on. 409 unless the batch is running. */
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @Operation(operationId = "pauseBatch")
    @ResponseStatus(HttpStatus.OK)
    @PostMapping(value = "/batches/{id}/pause", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<BatchResponse> pause(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        UUID currentUserId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(BatchResponse.from(batches.pause(currentUserId, id)));
    }

    /** Lets a paused batch start rows again at once. 409 unless the batch is paused. */
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @Operation(operationId = "resumeBatch")
    @ResponseStatus(HttpStatus.OK)
    @PostMapping(value = "/batches/{id}/resume", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<BatchResponse> resume(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        UUID currentUserId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(BatchResponse.from(batches.resume(currentUserId, id)));
    }

    /** Cancels a running or paused batch and its pending rows; live runs are asked to stop afterwards. */
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @Operation(operationId = "cancelBatch")
    @ResponseStatus(HttpStatus.OK)
    @PostMapping(value = "/batches/{id}/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<BatchResponse> cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        UUID currentUserId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(BatchResponse.from(batches.cancel(currentUserId, id)));
    }

    /** A new batch of the finished batch's failed rows; the batch can be retried once. */
    @ApiResponse(responseCode = "401", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "402", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @Operation(operationId = "retryBatch")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping(value = "/batches/{id}/retry", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<BatchResponse> retry(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        UUID currentUserId = currentUserResolver.require(jwt);
        return ResponseEntity.status(HttpStatus.CREATED).body(BatchResponse.from(batches.retry(currentUserId, id)));
    }
}
