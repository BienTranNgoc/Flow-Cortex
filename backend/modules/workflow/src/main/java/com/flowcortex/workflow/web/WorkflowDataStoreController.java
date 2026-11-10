package com.flowcortex.workflow.web;

import com.flowcortex.workflow.application.Caller;
import com.flowcortex.workflow.application.WorkflowDataStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/workflows/{id}/data} — the owner's key-value store of a workflow. Authenticated by {@code
 * :shared:security}'s default chain.
 */
@RestController
@RequestMapping("/api/v1/workflows/{id}/data")
class WorkflowDataStoreController {

    private final WorkflowDataStore dataStore;
    private final CurrentUserResolver currentUserResolver;

    WorkflowDataStoreController(WorkflowDataStore dataStore, CurrentUserResolver currentUserResolver) {
        this.dataStore = dataStore;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(operationId = "listWorkflowData")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "403", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<DataEntryListResponse> listWorkflowData(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        Caller caller = Caller.member(currentUserResolver.require(jwt));
        return ResponseEntity.ok(DataEntryListResponse.from(dataStore.list(caller, id)));
    }

    @Operation(operationId = "getWorkflowData")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "403", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping(value = "/{key}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<DataEntryResponse> getWorkflowData(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @PathVariable String key) {
        Caller caller = Caller.member(currentUserResolver.require(jwt));
        return ResponseEntity.ok(DataEntryResponse.from(dataStore.get(caller, id, key)));
    }

    @Operation(operationId = "putWorkflowData")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "400", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @PutMapping(value = "/{key}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<DataEntryResponse> putWorkflowData(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @PathVariable String key, @Valid @RequestBody PutDataEntryRequest request) {
        Caller caller = Caller.member(currentUserResolver.require(jwt));
        return ResponseEntity.ok(DataEntryResponse.from(
                dataStore.put(caller, id, key, DataStoreJson.toStore(request.value()), request.dataType())));
    }

    @Operation(operationId = "deleteWorkflowData")
    @ApiResponse(responseCode = "204")
    @ApiResponse(responseCode = "403", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/{key}")
    ResponseEntity<Void> deleteWorkflowData(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @PathVariable String key) {
        Caller caller = Caller.member(currentUserResolver.require(jwt));
        dataStore.delete(caller, id, key);
        return ResponseEntity.noContent().build();
    }
}
