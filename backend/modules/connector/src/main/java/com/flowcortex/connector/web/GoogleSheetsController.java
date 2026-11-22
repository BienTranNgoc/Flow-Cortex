package com.flowcortex.connector.web;

import com.flowcortex.connector.api.GoogleSheets;
import com.flowcortex.connector.api.SheetModels.Preview;
import com.flowcortex.connector.api.SheetModels.SpreadsheetPage;
import com.flowcortex.connector.api.SheetModels.Tabs;
import com.flowcortex.connector.application.google.GoogleSheetsRules;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/connectors/google/sheets} — the signed-in member's own spreadsheets. Query parameters are bound
 * as text and checked by {@link GoogleSheetsRules}, so a malformed value answers 400 with the connector problem
 * before any request reaches Google.
 */
@RestController
@RequestMapping("/api/v1/connectors/google/sheets")
@ApiResponse(responseCode = "400", description = "Malformed parameter (INVALID_INPUT)", content = @Content(
        mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "403", description = "Member is suspended (SUSPENDED)", content = @Content(
        mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "404", description = "No active Google connection (NOT_CONNECTED)", content = @Content(
        mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "409", description = "Usage switched off or credential unreadable", content = @Content(
        mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "422", description = "Refused (property reason names the cause)", content = @Content(
        mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "502", description = "Google unreachable (UPSTREAM_UNAVAILABLE)", content = @Content(
        mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "503", description = "Connector switched off or rate limited", content = @Content(
        mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
class GoogleSheetsController {

    private static final int DEFAULT_LIST_LIMIT = 50;
    private static final int DEFAULT_PREVIEW_LIMIT = 5;

    private final GoogleSheets sheets;
    private final ConnectorMember member;

    GoogleSheetsController(GoogleSheets sheets, ConnectorMember member) {
        this.sheets = sheets;
        this.member = member;
    }

    @Operation(operationId = "listGoogleSpreadsheets")
    @ApiResponse(responseCode = "200")
    @GetMapping
    SpreadsheetPage list(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String q,
            @RequestParam(required = false) String limit, @RequestParam(required = false) String cursor) {
        return sheets.listSpreadsheets(member.id(jwt), q, GoogleSheetsRules.intParam(limit, DEFAULT_LIST_LIMIT),
                cursor);
    }

    @Operation(operationId = "listGoogleSpreadsheetTabs")
    @ApiResponse(responseCode = "200")
    @GetMapping("/{id}/tabs")
    Tabs tabs(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return sheets.listTabs(member.id(jwt), id);
    }

    @Operation(operationId = "previewGoogleSpreadsheetTab")
    @ApiResponse(responseCode = "200")
    @GetMapping("/{id}/preview")
    Preview preview(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @RequestParam(required = false) String tab, @RequestParam(required = false) String range,
            @RequestParam(required = false) String limit, @RequestParam(required = false) String hasHeader) {
        return sheets.preview(member.id(jwt), id, tab, range, GoogleSheetsRules.intParam(limit, DEFAULT_PREVIEW_LIMIT),
                GoogleSheetsRules.boolParam(hasHeader, true));
    }
}
